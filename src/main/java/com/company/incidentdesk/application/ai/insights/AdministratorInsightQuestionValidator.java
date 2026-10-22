package com.company.incidentdesk.application.ai.insights;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validates and normalizes administrator-authored text before provider disclosure. */
public final class AdministratorInsightQuestionValidator {
    private static final int MAXIMUM_CODE_POINTS = 500;
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}(?![\\p{L}\\p{N}.-])");
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:\\b(?:https?|ftp)://|\\bwww\\.|\\b[a-z][a-z0-9+.-]*://)\\S+");
    private static final Pattern UUID = Pattern.compile(
            "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![0-9a-f])");

    public ValidatedQuestion validate(String rawQuestion, Collection<String> knownLoginNames) {
        Objects.requireNonNull(knownLoginNames, "knownLoginNames");
        if (rawQuestion == null) {
            throw rejected(Rejection.BLANK);
        }
        String question = rawQuestion.strip();
        if (question.isEmpty()) {
            throw rejected(Rejection.BLANK);
        }
        if (question.codePointCount(0, question.length()) > MAXIMUM_CODE_POINTS) {
            throw rejected(Rejection.TOO_LONG);
        }
        if (question.codePoints().anyMatch(Character::isISOControl)) {
            throw rejected(Rejection.CONTROL_CHARACTER);
        }
        if (EMAIL.matcher(question).find()) {
            throw rejected(Rejection.EMAIL_ADDRESS);
        }
        if (URL.matcher(question).find()) {
            throw rejected(Rejection.URL);
        }
        if (UUID.matcher(question).find()) {
            throw rejected(Rejection.IDENTIFIER);
        }

        String foldedQuestion = question.toLowerCase(Locale.ROOT);
        for (String loginName : knownLoginNames) {
            if (loginName != null && !loginName.isBlank()
                    && containsToken(foldedQuestion, loginName.toLowerCase(Locale.ROOT))) {
                throw rejected(Rejection.KNOWN_LOGIN_NAME);
            }
        }
        return new ValidatedQuestion(question);
    }

    private static boolean containsToken(String text, String token) {
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            int end = offset + token.length();
            boolean leftBoundary = offset == 0 || !isIdentifierCharacter(text.codePointBefore(offset));
            boolean rightBoundary = end == text.length() || !isIdentifierCharacter(text.codePointAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            offset += token.length();
        }
        return false;
    }

    private static boolean isIdentifierCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-';
    }

    private static QuestionRejectedException rejected(Rejection reason) {
        return new QuestionRejectedException(reason);
    }

    public record ValidatedQuestion(String value) {
        public ValidatedQuestion {
            Objects.requireNonNull(value, "value");
        }

        @Override public String toString() {
            return "Administrator insight question [content withheld]";
        }
    }

    public enum Rejection {
        BLANK, TOO_LONG, CONTROL_CHARACTER, EMAIL_ADDRESS, URL, IDENTIFIER, KNOWN_LOGIN_NAME
    }

    public static final class QuestionRejectedException extends RuntimeException {
        private final Rejection reason;

        private QuestionRejectedException(Rejection reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        public Rejection reason() {
            return reason;
        }
    }
}
