#include "frametelemetry.h"
#include "liveplotitem.h"

#include <QFontDatabase>
#include <QGuiApplication>
#include <QImage>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QScreen>
#include <QTimer>

#include <algorithm>
#include <cstdio>

namespace {
QString selectBrandFont()
{
    const auto families = QFontDatabase::families();
    for (const auto &candidate : {QStringLiteral("Helvetica Neue"),
                                  QStringLiteral("Inter"),
                                  QStringLiteral("Noto Sans")}) {
        if (families.contains(candidate))
            return candidate;
    }
    return QGuiApplication::font().family();
}

qsizetype visiblePixelScore(const QImage &source)
{
    if (source.isNull())
        return 0;
    const QImage image = source.convertToFormat(QImage::Format_RGB32);
    qsizetype score = 0;
    for (int y = 0; y < image.height(); ++y) {
        const auto *pixels = reinterpret_cast<const QRgb *>(image.constScanLine(y));
        for (int x = 0; x < image.width(); ++x) {
            const QRgb pixel = pixels[x];
            // The complete NOOP canvas is dark gray, not absolute black. A
            // partially rendered offscreen frame leaves most pixels at zero.
            if (qRed(pixel) + qGreen(pixel) + qBlue(pixel) > 24)
                ++score;
        }
    }
    return score;
}
} // namespace

int main(int argc, char *argv[])
{
    // Qt normally selects the threaded render loop on desktop. Pin it for this
    // performance control so an inherited environment cannot silently switch
    // scene-graph rendering back onto the GUI thread.
    if (!qEnvironmentVariableIsSet("QSG_RENDER_LOOP"))
        qputenv("QSG_RENDER_LOOP", QByteArrayLiteral("threaded"));

    QGuiApplication app(argc, argv);
    QCoreApplication::setApplicationName(QStringLiteral("NOOP Qt performance control"));
    QCoreApplication::setOrganizationName(QStringLiteral("NOOP"));
    QQuickStyle::setStyle(QStringLiteral("Basic"));

    qmlRegisterType<LivePlotItem>("Noop.Perf", 1, 0, "LivePlot");

    FrameTelemetry frameTelemetry;
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("FrameStats"), &frameTelemetry);
    engine.rootContext()->setContextProperty(QStringLiteral("BrandFont"), selectBrandFont());

    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed,
                     &app, [] { QCoreApplication::exit(EXIT_FAILURE); },
                     Qt::QueuedConnection);

    engine.loadFromModule(QStringLiteral("NoopLinux"), QStringLiteral("Main"));
    if (engine.rootObjects().isEmpty())
        return EXIT_FAILURE;

    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().constFirst());
    if (!window)
        return EXIT_FAILURE;

    const auto screens = QGuiApplication::screens();
    for (const QScreen *screen : screens) {
        const QByteArray name = screen->name().toUtf8();
        std::fprintf(stderr, "NOOP Qt spike: discovered screen=%s refresh=%.3f Hz\n",
                     name.constData(), screen->refreshRate());
    }

    // A benchmark window landing on the 240 Hz side display yields a perfectly
    // valid but irrelevant result. Select the highest-refresh output by default;
    // NOOP_TARGET_SCREEN can pin a specific Qt screen name for repeatability.
    const QString requestedScreen = qEnvironmentVariable("NOOP_TARGET_SCREEN");
    QScreen *targetScreen = nullptr;
    if (!requestedScreen.isEmpty()) {
        const auto requested = std::find_if(screens.cbegin(), screens.cend(),
                                            [&requestedScreen](const QScreen *screen) {
                                                return screen->name() == requestedScreen;
                                            });
        if (requested != screens.cend())
            targetScreen = *requested;
    }
    if (!targetScreen && !screens.isEmpty()) {
        targetScreen = *std::max_element(screens.cbegin(), screens.cend(),
                                         [](const QScreen *left, const QScreen *right) {
                                             return left->refreshRate() < right->refreshRate();
                                         });
    }
    if (targetScreen) {
        window->setScreen(targetScreen);
        const QRect available = targetScreen->availableGeometry();
        window->setPosition(available.center()
                            - QPoint(window->width() / 2, window->height() / 2));
    }

    frameTelemetry.attach(window);
    std::fprintf(stderr, "NOOP Qt spike: selected=%s screen=%.3f Hz target=%.5f Hz\n",
                 window->screen() ? window->screen()->name().toUtf8().constData() : "none",
                 window->screen() ? window->screen()->refreshRate() : 0.0,
                 frameTelemetry.targetHz());
    std::fflush(stderr);

    // Isolated visual regression seam. In CI run with the offscreen QPA and a
    // software RHI; this never needs access to the user's live compositor.
    const QString screenshotPath = qEnvironmentVariable("NOOP_SCREENSHOT_PATH");
    if (!screenshotPath.isEmpty()) {
        // Do not race an offscreen grab against the continuous benchmark
        // update chain. A cold software RHI can still yield one partially
        // rendered frame, so retain the best of four bounded attempts.
        frameTelemetry.setContinuous(false);
        auto *captureTimer = new QTimer(window);
        captureTimer->setInterval(300);
        QObject::connect(captureTimer, &QTimer::timeout, window,
                         [window, screenshotPath, captureTimer, attempts = 0,
                          best = QImage(), bestScore = qsizetype{0}]() mutable {
            const QImage candidate = window->grabWindow();
            const qsizetype candidateScore = visiblePixelScore(candidate);
            if (candidateScore > bestScore) {
                best = candidate;
                bestScore = candidateScore;
            }
            if (++attempts < 4)
                return;

            captureTimer->stop();
            const qsizetype pixels = static_cast<qsizetype>(best.width()) * best.height();
            const bool contentComplete = pixels > 0 && bestScore > pixels / 2;
            const bool saved = !best.isNull() && best.save(screenshotPath);
            std::fprintf(stderr,
                         "NOOP Qt spike: screenshot=%s size=%dx%d saved=%s content=%s "
                         "attempts=%d\n",
                         screenshotPath.toUtf8().constData(), best.width(), best.height(),
                         saved ? "yes" : "no", contentComplete ? "complete" : "partial", attempts);
            std::fflush(stderr);
            QCoreApplication::exit(saved && contentComplete ? EXIT_SUCCESS : EXIT_FAILURE);
        });
        QTimer::singleShot(700, captureTimer, qOverload<>(&QTimer::start));
    }

    return app.exec();
}
