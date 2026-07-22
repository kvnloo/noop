#pragma once

#include <QObject>
#include <QTimer>

#include <array>
#include <atomic>

class QQuickWindow;

// A deliberately allocation-free render-thread probe. QQuickWindow emits
// afterFrameEnd on the render thread; that callback only writes a timestamp
// interval to this atomic SPSC ring. Sorting, percentiles, strings, and QML
// notifications happen later on the GUI thread.
class FrameTelemetry final : public QObject
{
    Q_OBJECT
    Q_PROPERTY(double targetHz READ targetHz CONSTANT)
    Q_PROPERTY(double targetFrameMs READ targetFrameMs CONSTANT)
    Q_PROPERTY(double screenRefreshHz READ screenRefreshHz NOTIFY environmentChanged)
    Q_PROPERTY(QString backend READ backend NOTIFY environmentChanged)
    Q_PROPERTY(bool continuous READ continuous WRITE setContinuous NOTIFY continuousChanged)
    Q_PROPERTY(qulonglong sampleCount READ sampleCount NOTIFY metricsChanged)
    Q_PROPERTY(double fps READ fps NOTIFY metricsChanged)
    Q_PROPERTY(double p50Ms READ p50Ms NOTIFY metricsChanged)
    Q_PROPERTY(double p95Ms READ p95Ms NOTIFY metricsChanged)
    Q_PROPERTY(double p99Ms READ p99Ms NOTIFY metricsChanged)
    Q_PROPERTY(double worstMs READ worstMs NOTIFY metricsChanged)
    Q_PROPERTY(double overBudgetPercent READ overBudgetPercent NOTIFY metricsChanged)
    Q_PROPERTY(QString verdict READ verdict NOTIFY metricsChanged)

public:
    explicit FrameTelemetry(QObject *parent = nullptr);

    void attach(QQuickWindow *window);

    [[nodiscard]] double targetHz() const { return kTargetHz; }
    [[nodiscard]] double targetFrameMs() const { return 1000.0 / kTargetHz; }
    [[nodiscard]] double screenRefreshHz() const { return m_screenRefreshHz; }
    [[nodiscard]] QString backend() const { return m_backend; }
    [[nodiscard]] bool continuous() const { return m_continuous; }
    void setContinuous(bool enabled);
    [[nodiscard]] qulonglong sampleCount() const { return m_sampleCount; }
    [[nodiscard]] double fps() const { return m_fps; }
    [[nodiscard]] double p50Ms() const { return m_p50Ms; }
    [[nodiscard]] double p95Ms() const { return m_p95Ms; }
    [[nodiscard]] double p99Ms() const { return m_p99Ms; }
    [[nodiscard]] double worstMs() const { return m_worstMs; }
    [[nodiscard]] double overBudgetPercent() const { return m_overBudgetPercent; }
    [[nodiscard]] QString verdict() const { return m_verdict; }

    Q_INVOKABLE void reset();

signals:
    void metricsChanged();
    void environmentChanged();
    void continuousChanged();

private slots:
    void poll();
    void updateEnvironment();

private:
    void recordFrame();

    static constexpr double kTargetHz = 540.16498;
    static constexpr std::size_t kCapacity = 16384; // ~30 s at the target output.

    QQuickWindow *m_window = nullptr;
    QMetaObject::Connection m_continuousConnection;
    bool m_continuous = true;
    QTimer m_pollTimer;
    std::array<std::atomic<qint64>, kCapacity> m_intervalsNs{};
    std::atomic<quint64> m_written{0};
    std::atomic<qint64> m_lastFrameNs{0};
    std::atomic_bool m_resetRequested{false};

    double m_screenRefreshHz = 0.0;
    QString m_backend = QStringLiteral("initializing");
    qulonglong m_sampleCount = 0;
    double m_fps = 0.0;
    double m_p50Ms = 0.0;
    double m_p95Ms = 0.0;
    double m_p99Ms = 0.0;
    double m_worstMs = 0.0;
    double m_overBudgetPercent = 0.0;
    QString m_verdict = QStringLiteral("collecting");
    int m_pollsSinceLog = 0;
};
