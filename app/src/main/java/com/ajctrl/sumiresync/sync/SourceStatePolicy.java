package com.ajctrl.sumiresync.sync;

/** Pure decision logic for safely reconciling local progress with a Provider status. */
public final class SourceStatePolicy {
    public enum Decision { RESET, CONTINUE }

    private SourceStatePolicy() {}

    public static Decision evaluate(int supportedApiVersion, int remoteApiVersion,
                                    String remoteInstanceId, int remoteGeneration,
                                    long remoteSequence, String localInstanceId,
                                    Integer localGeneration, long localLastId) {
        if (remoteApiVersion != supportedApiVersion) {
            throw new IllegalStateException("Unsupported Provider API version: " + remoteApiVersion);
        }
        if (remoteInstanceId == null || remoteInstanceId.trim().isEmpty()) {
            throw new IllegalStateException("Provider returned an empty databaseInstanceId");
        }
        if (localInstanceId == null || !remoteInstanceId.equals(localInstanceId)
                || localGeneration == null || remoteGeneration != localGeneration) {
            return Decision.RESET;
        }
        if (remoteSequence < localLastId) {
            throw new IllegalStateException(
                    "Sumire sequence moved backwards without an instance/generation change");
        }
        return Decision.CONTINUE;
    }
}
