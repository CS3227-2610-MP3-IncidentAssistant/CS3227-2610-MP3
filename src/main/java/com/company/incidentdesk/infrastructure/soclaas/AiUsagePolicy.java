package com.company.incidentdesk.infrastructure.soclaas;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.core.env.Environment;

/** Backend-only verified accounting inputs. No provider key or user content belongs here. */
public final class AiUsagePolicy {
    private AiUsagePolicy() { }

    public record Price(String model, long inputPerMillion, long outputPerMillion) {
        public Price {
            if (model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}")
                    || inputPerMillion < 0 || outputPerMillion < 0) {
                throw new IllegalArgumentException("Invalid AI model price");
            }
        }

        public static Price fromCatalog(SoclaasClient.Model model) {
            try {
                var metadata = model.metadata();
                var input = metadata.path("soclaas").path("input_microdollars_per_million_tokens");
                var output = metadata.path("soclaas").path("output_microdollars_per_million_tokens");
                if (!model.id().equals(metadata.path("id").asString(""))
                        || !input.isIntegralNumber() || !input.canConvertToLong()
                        || !output.isIntegralNumber() || !output.canConvertToLong()) {
                    throw new AiUsageException(AiUsageException.Category.AI_UNAVAILABLE);
                }
                return new Price(model.id(), input.asLong(), output.asLong());
            } catch (RuntimeException failure) {
                throw new AiUsageException(AiUsageException.Category.AI_UNAVAILABLE);
            }
        }

        /** Ceil the combined cost once; microdollars are indivisible ledger units. */
        public long cost(long input, long output) {
            if (input < 0 || output < 0) {
                throw new IllegalArgumentException("Invalid AI token counts");
            }
            try {
                return BigInteger.valueOf(input).multiply(BigInteger.valueOf(inputPerMillion))
                        .add(BigInteger.valueOf(output).multiply(BigInteger.valueOf(outputPerMillion)))
                        .add(BigInteger.valueOf(999999)).divide(BigInteger.valueOf(1000000)).longValueExact();
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("AI accounting cost exceeds supported range");
            }
        }
    }

    /** Null lifetime means verified uncapped, not unknown; zero means exhausted. */
    public record Snapshot(Instant verifiedAt, int requestsPerMinute, long daily, long monthly,
            Long lifetime, long daySpent, long monthSpent, long lifetimeSpent, Price price) {
        public Snapshot {
            Objects.requireNonNull(verifiedAt, "AI verification time is required");
            Objects.requireNonNull(price, "AI model price is required");
            if (requestsPerMinute < 0 || daily < 0 || monthly < 0 || (lifetime != null && lifetime < 0)
                    || daySpent < 0 || monthSpent < 0 || lifetimeSpent < 0) {
                throw new IllegalArgumentException("Invalid AI provider allowance");
            }
        }

        public boolean freshAt(Instant now) {
            return !now.isBefore(verifiedAt) && Duration.between(verifiedAt, now).compareTo(Duration.ofMinutes(15)) < 0
                    && verifiedAt.atOffset(ZoneOffset.UTC).toLocalDate().equals(now.atOffset(ZoneOffset.UTC).toLocalDate());
        }

        public int requestCeiling() {
            return (int) Math.min(60, (long) requestsPerMinute * 2 / 3);
        }
    }

    public record Limits(int accountMinute, int accountHour, int accountDay, int globalMinute,
            int concurrent, long daily, long monthly, long lifetime) {
        public Limits {
            if (accountMinute < 1 || accountMinute > 5 || accountHour < 1 || accountHour > 30
                    || accountDay < 1 || accountDay > 100 || globalMinute < 1 || globalMinute > 60
                    || concurrent < 1 || concurrent > 8 || daily < 1 || daily > 60000000
                    || monthly < 1 || monthly > 600000000 || lifetime < 1) {
                throw new IllegalArgumentException("Invalid AI usage limits");
            }
        }

        public static Limits from(Environment environment) {
            return new Limits((int) read(environment, "AI_ACCOUNT_REQUESTS_PER_MINUTE", 5),
                    (int) read(environment, "AI_ACCOUNT_REQUESTS_PER_HOUR", 30),
                    (int) read(environment, "AI_ACCOUNT_REQUESTS_PER_DAY", 100),
                    (int) read(environment, "AI_GLOBAL_REQUESTS_PER_MINUTE", 60),
                    (int) read(environment, "AI_MAX_CONCURRENT_REQUESTS", 8),
                    read(environment, "AI_DAILY_MICRODOLLARS", 60000000),
                    read(environment, "AI_MONTHLY_MICRODOLLARS", 600000000),
                    read(environment, "AI_LIFETIME_MICRODOLLARS", Long.MAX_VALUE));
        }

        private static long read(Environment environment, String name, long maximum) {
            try {
                long value = Long.parseLong(environment.getProperty(name, Long.toString(maximum)));
                if (value > 0 && value <= maximum) { return value; }
            } catch (NumberFormatException failure) {
                // Supplied values must not appear in errors or parser causes.
            }
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    public static long reserveCeiling(long allowance) {
        return BigInteger.valueOf(allowance).multiply(BigInteger.valueOf(3))
                .divide(BigInteger.valueOf(4)).longValueExact();
    }
}
