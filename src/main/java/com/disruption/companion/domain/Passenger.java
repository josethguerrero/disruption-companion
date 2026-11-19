package com.disruption.companion.domain;

public record Passenger(String passengerId, String name, String type, Integer age,
        String email, String phone, String documentId, String loyaltyTier) {

    public boolean isMinor() {
        return "CHILD".equals(type) || "INFANT".equals(type) || (age != null && age < 18);
    }

    // Keep PII out of logs.
    @Override
    public String toString() {
        return "Passenger[" + passengerId + ", " + type + "]";
    }
}