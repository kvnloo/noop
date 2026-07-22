#include "frametelemetry.h"

#include <QQuickWindow>
#include <QScreen>
#include <QSGRendererInterface>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <numeric>
#include <vector>

namespace {
qint64 monotonicNanoseconds()
{
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

double percentile(const std::vector<qint64> &sorted, const double q)
{
    if (sorted.empty())
        return 0.0;
    const auto index = static_cast<std::size_t>(
        std::clamp(q, 0.0, 1.0) * static_cast<double>(sorted.size() - 1));
    return static_cast<double>(sorted[index]) / 1'000'000.0;
}

QString graphicsApiName(const QSGRendererInterface::GraphicsApi api)
{
    switch (api) {
    case QSGRendererInterface::Software: return QStringLiteral("software");
    case QSGRendererInterface::OpenGL: return QStringLiteral("OpenGL");
    case QSGRendererInterface::Vulkan: return QStringLiteral("Vulkan");
    case QSGRendererInterface::Metal: return QStringLiteral("Metal");
    case QSGRendererInterface::Direct3D11: return QStringLiteral("Direct3D 11");
    case QSGRendererInterface::Direct3D12: return QStringLiteral("Direct3D 12");
    case QSGRendererInterface::Null: return QStringLiteral("null");
    default: return QStringLiteral("unknown");
    }
}
} // namespace

FrameTelemetry::FrameTelemetry(QObject *parent)
    : QObject(parent)
{
    m_pollTimer.setInterval(500);
    m_pollTimer.setTimerType(Qt::CoarseTimer);
    connect(&m_pollTimer, &QTimer::timeout, this, &FrameTelemetry::poll);
}

void FrameTelemetry::attach(QQuickWindow *window)
{
    if (!window || m_window == window)
        return;

    m_window = window;
    // DirectConnection is intentional: the timestamp is captured at the real
    // render-thread frame boundary. recordFrame() must remain allocation-free.
    connect(window, &QQuickWindow::afterFrameEnd,
            this, &FrameTelemetry::recordFrame, Qt::DirectConnection);
    connect(window, &QQuickWindow::screenChanged,
            this, &FrameTelemetry::updateEnvironment);
    connect(window, &QQuickWindow::sceneGraphInitialized,
            this, [this] { QMetaObject::invokeMethod(this, &FrameTelemetry::updateEnvironment,
                                                      Qt::QueuedConnection); },
            Qt::DirectConnection);

    updateEnvironment();
    // The RHI backend can still report Unknown before the first scene-graph
    // initialization. Refresh it after the window has presented a few frames.
    QTimer::singleShot(1000, this, &FrameTelemetry::updateEnvironment);
    m_pollTimer.start();
    // Re-apply the default now that a window exists. frameSwapped -> update is
    // a self-paced chain: one queued GUI event per presented frame, no busy
    // zero-duration timer and no dependence on the platform animation tick.
    const bool requested = m_continuous;
    m_continuous = false;
    setContinuous(requested);
}

void FrameTelemetry::setContinuous(const bool enabled)
{
    if (m_continuous == enabled && (enabled == static_cast<bool>(m_continuousConnection)))
        return;

    if (m_continuousConnection)
        disconnect(m_continuousConnection);
    m_continuousConnection = {};
    m_continuous = enabled;

    if (m_continuous && m_window) {
        m_continuousConnection = connect(
            m_window, &QQuickWindow::frameSwapped, m_window,
            [window = m_window] { window->update(); }, Qt::QueuedConnection);
        m_window->update();
    }
    emit continuousChanged();
}

void FrameTelemetry::recordFrame()
{
    if (m_resetRequested.exchange(false, std::memory_order_acq_rel)) {
        m_written.store(0, std::memory_order_release);
        m_lastFrameNs.store(0, std::memory_order_release);
    }

    const qint64 now = monotonicNanoseconds();
    const qint64 previous = m_lastFrameNs.exchange(now, std::memory_order_acq_rel);
    if (previous == 0)
        return;

    const quint64 sequence = m_written.load(std::memory_order_relaxed);
    m_intervalsNs[sequence % kCapacity].store(now - previous, std::memory_order_relaxed);
    m_written.store(sequence + 1, std::memory_order_release);
}

void FrameTelemetry::poll()
{
    const quint64 written = m_written.load(std::memory_order_acquire);
    const std::size_t count = static_cast<std::size_t>(
        std::min<quint64>(written, static_cast<quint64>(kCapacity)));

    std::vector<qint64> values;
    values.reserve(count);
    const quint64 first = written > count ? written - count : 0;
    for (quint64 sequence = first; sequence < written; ++sequence) {
        const qint64 value = m_intervalsNs[sequence % kCapacity].load(std::memory_order_relaxed);
        if (value > 0)
            values.push_back(value);
    }

    if (values.empty())
        return;

    const auto sum = std::accumulate(values.cbegin(), values.cend(), 0.0L);
    const double averageNs = static_cast<double>(sum / static_cast<long double>(values.size()));
    const double budgetNs = 1'000'000'000.0 / kTargetHz;
    const auto overBudget = std::count_if(values.cbegin(), values.cend(), [budgetNs](const qint64 ns) {
        // Explicit 2% timestamp tolerance. This is still a strict one-refresh
        // budget; a two-refresh p99 can never receive an ON BUDGET verdict.
        return static_cast<double>(ns) > budgetNs * 1.02;
    });

    std::sort(values.begin(), values.end());
    m_sampleCount = written;
    m_fps = averageNs > 0.0 ? 1'000'000'000.0 / averageNs : 0.0;
    m_p50Ms = percentile(values, 0.50);
    m_p95Ms = percentile(values, 0.95);
    m_p99Ms = percentile(values, 0.99);
    m_worstMs = static_cast<double>(values.back()) / 1'000'000.0;
    m_overBudgetPercent = 100.0 * static_cast<double>(overBudget) /
                          static_cast<double>(values.size());

    if (values.size() < static_cast<std::size_t>(kTargetHz * 3.0)) {
        m_verdict = QStringLiteral("COLLECTING");
    } else if (m_p50Ms <= targetFrameMs() * 1.02
               && m_p95Ms <= targetFrameMs() * 1.02
               && m_p99Ms <= targetFrameMs() * 1.02) {
        m_verdict = QStringLiteral("ON 540 HZ BUDGET");
    } else {
        m_verdict = QStringLiteral("BELOW 540 HZ TARGET");
    }
    emit metricsChanged();

    if (++m_pollsSinceLog >= 10) {
        m_pollsSinceLog = 0;
        const QByteArray verdictBytes = m_verdict.toUtf8();
        std::fprintf(stderr,
                     "frame-control samples=%llu fps=%.1f p50=%.3fms p95=%.3fms "
                     "p99=%.3fms worst=%.3fms late=%.2f%% %s\n",
                     static_cast<unsigned long long>(written), m_fps, m_p50Ms,
                     m_p95Ms, m_p99Ms, m_worstMs, m_overBudgetPercent,
                     verdictBytes.constData());
        std::fflush(stderr);
    }
}

void FrameTelemetry::updateEnvironment()
{
    if (!m_window)
        return;

    m_screenRefreshHz = m_window->screen() ? m_window->screen()->refreshRate() : 0.0;
    m_backend = graphicsApiName(m_window->rendererInterface()->graphicsApi());
    emit environmentChanged();
}

void FrameTelemetry::reset()
{
    m_resetRequested.store(true, std::memory_order_release);
    m_sampleCount = 0;
    m_fps = 0.0;
    m_p50Ms = 0.0;
    m_p95Ms = 0.0;
    m_p99Ms = 0.0;
    m_worstMs = 0.0;
    m_overBudgetPercent = 0.0;
    m_verdict = QStringLiteral("COLLECTING");
    m_pollsSinceLog = 0;
    emit metricsChanged();
}
