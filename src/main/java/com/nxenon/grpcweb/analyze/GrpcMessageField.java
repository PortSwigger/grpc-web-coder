package com.nxenon.grpcweb.analyze;

import java.util.Objects;

/**
 * One field of a protobuf message, recovered from a generated JavaScript setter.
 *
 * <p>Field numbers matter more than names here: pairing a name with its number is what lets a
 * decoded payload's bare {@code "3"} be understood, and lets the Type Definition tab be filled in
 * with real names.
 */
public final class GrpcMessageField implements Comparable<GrpcMessageField> {

    private final String messageName;
    private final String fieldName;
    private final int fieldNumber;
    private final String jspbSetter;
    private final String inferredType;

    GrpcMessageField(String messageName, String fieldName, int fieldNumber, String jspbSetter,
                     String inferredType) {
        this.messageName = messageName;
        this.fieldName = fieldName;
        this.fieldNumber = fieldNumber;
        this.jspbSetter = jspbSetter;
        this.inferredType = inferredType;
    }

    /** The fully-qualified message name, e.g. {@code auth.LoginRequest}. */
    public String messageName() {
        return messageName;
    }

    /** The field name as written in the {@code .proto}, e.g. {@code user_name}. */
    public String fieldName() {
        return fieldName;
    }

    public int fieldNumber() {
        return fieldNumber;
    }

    /** The raw jspb setter the field came from, e.g. {@code Proto3StringField}. */
    public String jspbSetter() {
        return jspbSetter;
    }

    /**
     * The field type in the vocabulary of the Type Definition tab, e.g. {@code string}, or
     * {@code ?} when the setter does not say.
     */
    public String inferredType() {
        return inferredType;
    }

    @Override
    public int compareTo(GrpcMessageField other) {
        int byMessage = messageName.compareTo(other.messageName);
        return byMessage != 0 ? byMessage : Integer.compare(fieldNumber, other.fieldNumber);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GrpcMessageField field
                && messageName.equals(field.messageName)
                && fieldNumber == field.fieldNumber
                && fieldName.equals(field.fieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageName, fieldName, fieldNumber);
    }

    @Override
    public String toString() {
        return messageName + "." + fieldName + " = " + fieldNumber + " (" + inferredType + ")";
    }
}
