#include "liveplotitem.h"

#include <QSGFlatColorMaterial>
#include <QSGClipNode>
#include <QSGGeometry>
#include <QSGGeometryNode>

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace {
class PlotNode final : public QSGNode
{
public:
    PlotNode()
    {
        clip = new QSGClipNode;
        clip->setIsRectangular(true);
        appendChildNode(clip);

        auto *geometry = new QSGGeometry(QSGGeometry::defaultAttributes_Point2D(), 0);
        geometry->setDrawingMode(QSGGeometry::DrawTriangleStrip);
        geometry->setVertexDataPattern(QSGGeometry::DynamicPattern);
        line = new QSGGeometryNode;
        line->setGeometry(geometry);
        line->setFlag(QSGNode::OwnsGeometry);

        auto *material = new QSGFlatColorMaterial;
        line->setMaterial(material);
        line->setFlag(QSGNode::OwnsMaterial);
        clip->appendChildNode(line);
    }

    QSGClipNode *clip = nullptr;
    QSGGeometryNode *line = nullptr;
};
} // namespace

LivePlotItem::LivePlotItem(QQuickItem *parent)
    : QQuickItem(parent)
{
    setFlag(ItemHasContents, true);
    m_samples.fill(0.0, kSampleCapacity);

    // This timer stands in for a sensor publisher. The real BLE/analytics bridge
    // calls appendSample() only when a sample arrives. Geometry therefore rebuilds
    // at the data rate (62.5 Hz here), never at the 540 Hz presentation rate.
    m_demoTimer.setTimerType(Qt::PreciseTimer);
    m_demoTimer.setInterval(16);
    connect(&m_demoTimer, &QTimer::timeout, this, &LivePlotItem::appendDemoSample);
    m_demoTimer.start();
}

void LivePlotItem::setLineColor(const QColor &color)
{
    if (m_lineColor == color)
        return;
    m_lineColor = color;
    emit lineColorChanged();
    update();
}

void LivePlotItem::setBpm(const qreal bpm)
{
    const qreal clamped = std::clamp<qreal>(bpm, 30.0, 220.0);
    if (qFuzzyCompare(m_bpm, clamped))
        return;
    m_bpm = clamped;
    emit bpmChanged();
}

void LivePlotItem::setDemoRunning(const bool running)
{
    if (running == m_demoTimer.isActive())
        return;
    running ? m_demoTimer.start() : m_demoTimer.stop();
    emit demoRunningChanged();
}

void LivePlotItem::appendSample(const qreal normalizedSample)
{
    if (m_samples.size() >= kSampleCapacity)
        m_samples.removeFirst();
    m_samples.append(std::clamp<qreal>(normalizedSample, -1.0, 1.0));
    update();
}

void LivePlotItem::clear()
{
    m_samples.fill(0.0, kSampleCapacity);
    update();
}

void LivePlotItem::appendDemoSample()
{
    constexpr double stepSeconds = 0.016;
    m_demoTime += stepSeconds;
    const double beatPhase = std::fmod(m_demoTime * static_cast<double>(m_bpm) / 60.0, 1.0);
    const auto gaussian = [beatPhase](const double center, const double width, const double height) {
        const double delta = (beatPhase - center) / width;
        return height * std::exp(-(delta * delta));
    };
    const double respiratoryDrift = 0.05 * std::sin(m_demoTime * 2.0);
    const double sample = respiratoryDrift
                          + gaussian(0.08, 0.026, 0.22)
                          - gaussian(0.12, 0.014, 0.35)
                          + gaussian(0.15, 0.012, 0.92)
                          - gaussian(0.18, 0.020, 0.30)
                          + gaussian(0.38, 0.070, 0.16);
    appendSample(static_cast<qreal>(sample));
}

QSGNode *LivePlotItem::updatePaintNode(QSGNode *oldNode, UpdatePaintNodeData *)
{
    auto *root = static_cast<PlotNode *>(oldNode);
    if (!root)
        root = new PlotNode;

    root->clip->setClipRect(boundingRect());
    auto *node = root->line;

    // Qt's updatePaintNode synchronization phase blocks the GUI thread while
    // this executes on the render thread, so copying the GUI-owned sample ring
    // here is safe without a mutex. No QObjects are touched after this method.
    auto *geometry = node->geometry();
    geometry->allocate(static_cast<int>(m_samples.size() * 2));
    auto *vertices = geometry->vertexDataAsPoint2D();

    const qreal plotWidth = width();
    const qreal plotHeight = height();
    const qreal divisor = std::max<qreal>(1.0, static_cast<qreal>(m_samples.size() - 1));
    const auto pointAt = [&](const qsizetype index) {
        return QPointF(static_cast<qreal>(index) / divisor * plotWidth,
                       plotHeight * (0.5 - m_samples[index] * 0.40));
    };
    constexpr qreal halfWidth = 1.25;
    for (qsizetype index = 0; index < m_samples.size(); ++index) {
        const QPointF point = pointAt(index);
        const QPointF previous = pointAt(std::max<qsizetype>(0, index - 1));
        const QPointF next = pointAt(std::min<qsizetype>(m_samples.size() - 1, index + 1));
        const qreal dx = next.x() - previous.x();
        const qreal dy = next.y() - previous.y();
        const qreal length = std::max<qreal>(std::hypot(dx, dy), 0.001);
        const QPointF normal(-dy / length * halfWidth, dx / length * halfWidth);
        vertices[index * 2].set(static_cast<float>((point + normal).x()),
                                static_cast<float>((point + normal).y()));
        vertices[index * 2 + 1].set(static_cast<float>((point - normal).x()),
                                    static_cast<float>((point - normal).y()));
    }

    auto *material = static_cast<QSGFlatColorMaterial *>(node->material());
    material->setColor(m_lineColor);
    node->markDirty(QSGNode::DirtyGeometry | QSGNode::DirtyMaterial);
    const bool firstGeometry = !m_loggedFirstGeometry.exchange(true, std::memory_order_relaxed);
    if (firstGeometry && qEnvironmentVariableIsSet("NOOP_TRACE_PLOT")) {
        std::fprintf(stderr,
                     "live-plot first-build width=%.1f height=%.1f samples=%lld vertices=%d color=%s\n",
                     static_cast<double>(plotWidth), static_cast<double>(plotHeight),
                     static_cast<long long>(m_samples.size()), geometry->vertexCount(),
                     m_lineColor.name(QColor::HexArgb).toUtf8().constData());
        std::fflush(stderr);
    }
    return root;
}
