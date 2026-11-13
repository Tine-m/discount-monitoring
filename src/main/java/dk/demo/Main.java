
package dk.demo;

import io.javalin.Javalin;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.Locale;

public class Main {

    // Feature flag: rabatfunktionen er deaktiveret ved opstart.
    private static volatile boolean discountEnabled = false;

    /*
     * Opret et register til applikationens metrics.
     * PrometheusMeterRegistry er Micrometers integration til Prometheus.
     */
    private static final PrometheusMeterRegistry metrics =
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    // Business metrics: Counters og Timer.
    private static final Counter attempts = Counter.builder("discount_attempts")
            .description("Discount attempts while feature is enabled")
            .register(metrics);

    private static final Counter applied = Counter.builder("discount_applied")
            .description("Successfully applied discounts")
            .register(metrics);

    private static final Counter rejected = Counter.builder("discount_rejected")
            .description("Rejected discount codes")
            .register(metrics);

    private static final Counter errors = Counter.builder("discount_errors")
            .description("Unexpected discount calculation errors")
            .register(metrics);

    private static final Timer discountDuration = Timer.builder("discount_calculation_duration")
            .description("Time spent processing discount attempts")
            .register(metrics);

    public static void main(String[] args) {

        Javalin app = Javalin.create(config ->
                config.http.defaultContentType = "application/json");

        /*
         * Registrer HTTP-metrics efter hvert request.
         * Vi anvender faste route-kategorier frem for rå URL'er
         * for at undgå for mange forskellige label-værdier.
         */
        app.after(ctx -> {
            String path = ctx.path();

            String route = path.equals("/api/discount") ? "discount" :
                    path.equals("/api/health") ? "health" :
                            path.equals("/admin/discount-feature") ? "feature_flag" : "other";

            // Undgå at tælle Prometheus' egne kald til /metrics.
            if (!path.equals("/metrics")) {
                Counter.builder("demo_http_requests")
                        .tag("route", route)
                        .tag("status", Integer.toString(ctx.status().getCode()))
                        .register(metrics)
                        .increment();
            }
        });

        // Health endpoint.
        app.get("/api/health", ctx ->
                ctx.result("{\"status\":\"UP\"}"));

        // Vis feature flagets aktuelle status.
        app.get("/api/feature-status", ctx ->
                ctx.result("{\"discountEnabled\":" + discountEnabled + "}"));

        /*
         * Aktivér eller deaktivér rabatfunktionen.
         * OBS: endpointet har ingen autentificering.
         */
        app.post("/admin/discount-feature", ctx -> {
            String value = ctx.queryParam("enabled");

            if (!"true".equals(value) && !"false".equals(value)) {
                ctx.status(400).result(
                        "{\"error\":\"Use ?enabled=true or ?enabled=false\"}");
                return;
            }

            discountEnabled = Boolean.parseBoolean(value);

            ctx.result("{\"discountEnabled\":" + discountEnabled + "}");
        });

        // Beregn rabat.
        app.get("/api/discount", ctx -> {

            if (!discountEnabled) {
                ctx.status(409).result(
                        "{\"error\":\"Discount feature is disabled\"}");
                return;
            }

            attempts.increment();
            Timer.Sample sample = Timer.start(metrics);

            try {
                String code = ctx.queryParam("code");
                String amountText = ctx.queryParam("amount");

                double amount;

                try {
                    amount = Double.parseDouble(
                            amountText == null ? "" : amountText);
                } catch (NumberFormatException e) {
                    rejected.increment();
                    ctx.status(400).result(
                            "{\"error\":\"Invalid amount\"}");
                    return;
                }

                if (!Double.isFinite(amount) || amount < 0 || amount > 1_000_000) {
                    rejected.increment();
                    ctx.status(400).result(
                            "{\"error\":\"Amount must be between 0 and 1000000\"}");
                    return;
                }

                // Simuleret teknisk fejl til monitoring-øvelsen.
                if ("CRASH".equalsIgnoreCase(code)) {
                    throw new IllegalStateException(
                            "Simulated discount calculation failure");
                }

                if (!"SAVE10".equalsIgnoreCase(code)) {
                    rejected.increment();
                    ctx.status(400).result(
                            "{\"error\":\"Unknown discount code\"}");
                    return;
                }

                // Succesfuld rabatberegning.
                applied.increment();

                ctx.result(String.format(Locale.US,
                        "{\"originalAmount\":%.2f,\"discountPercent\":10,\"finalAmount\":%.2f}",
                        amount, amount * 0.9));

            } catch (Exception e) {
                errors.increment();

                System.err.println(
                        "ERROR discount calculation: " + e.getMessage());

                ctx.status(500).result(
                        "{\"error\":\"Internal discount error\"}");

            } finally {
                // Registrer behandlingstiden, også ved fejl.
                sample.stop(discountDuration);
            }
        });

        // Eksponér metrics, så Prometheus kan hente dem.
        app.get("/metrics", ctx ->
                ctx.contentType("text/plain; version=0.0.4; charset=utf-8")
                        .result(metrics.scrape()));

        app.start(8080);
    }
}
