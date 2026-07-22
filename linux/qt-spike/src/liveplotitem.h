#pragma once

#include <QColor>
#include <QQuickItem>
#include <QTimer>
#include <QVector>

#include <atomic>

// qmlRegisterType wraps constructible types in QQmlElement<T>, so this class
// intentionally cannot be final.
class LivePlotItem : public QQuickItem
{
    Q_OBJECT
    Q_PROPERTY(QColor lineColor READ lineColor WRITE setLineColor NOTIFY lineColorChanged)
    Q_PROPERTY(qreal bpm READ bpm WRITE setBpm NOTIFY bpmChanged)
    Q_PROPERTY(bool demoRunning READ demoRunning WRITE setDemoRunning NOTIFY demoRunningChanged)

public:
    explicit LivePlotItem(QQuickItem *parent = nullptr);

    [[nodiscard]] QColor lineColor() const { return m_lineColor; }
    void setLineColor(const QColor &color);
    [[nodiscard]] qreal bpm() const { return m_bpm; }
    void setBpm(qreal bpm);
    [[nodiscard]] bool demoRunning() const { return m_demoTimer.isActive(); }
    void setDemoRunning(bool running);
    Q_INVOKABLE void appendSample(qreal normalizedSample);
    Q_INVOKABLE void clear();

signals:
    void lineColorChanged();
    void bpmChanged();
    void demoRunningChanged();

protected:
    QSGNode *updatePaintNode(QSGNode *oldNode, UpdatePaintNodeData *) override;

private slots:
    void appendDemoSample();

private:
    static constexpr qsizetype kSampleCapacity = 384;

    QColor m_lineColor = QColor(QStringLiteral("#ff6b81"));
    qreal m_bpm = 72.0;
    QVector<qreal> m_samples;
    QTimer m_demoTimer;
    double m_demoTime = 0.0;
    std::atomic_bool m_loggedFirstGeometry{false};
};
