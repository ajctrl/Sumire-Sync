package com.ajctrl.sumiresync.upload;

/** Formats nested exceptions for the in-app status view without exposing a stack trace. */
public final class ErrorDetails {
    private static final int MAX_CAUSES = 6;

    private ErrorDetails() {}

    public static String describe(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (depth > 0) result.append("\n原因: ");
            result.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) result.append(": ").append(message);
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return result.toString();
    }
}
