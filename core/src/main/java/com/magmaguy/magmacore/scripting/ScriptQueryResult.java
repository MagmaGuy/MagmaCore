package com.magmaguy.magmacore.scripting;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Classloader-neutral result of a synchronous Lua hook query.
 *
 * <p>Queries deliberately expose only immutable Lua scalar values. Rich runtime objects and LuaJ
 * values remain inside MagmaCore, so callers cannot retain VM-owned state past the hook call.</p>
 */
public record ScriptQueryResult(
        Kind kind,
        String text,
        Double number,
        Boolean flag) {

    public enum Kind {
        /** The script is closed or does not declare the queried hook. */
        UNHANDLED,
        /** The hook ran successfully and returned nil or no value. */
        NIL,
        STRING,
        NUMBER,
        BOOLEAN,
        /** Hook execution or result validation failed and the script was shut down. */
        FAILED
    }

    public ScriptQueryResult {
        Objects.requireNonNull(kind, "kind");
        int values = (text == null ? 0 : 1) + (number == null ? 0 : 1) + (flag == null ? 0 : 1);
        int expectedValues = switch (kind) {
            case STRING, NUMBER, BOOLEAN -> 1;
            case UNHANDLED, NIL, FAILED -> 0;
        };
        if (values != expectedValues
                || (kind == Kind.STRING && text == null)
                || (kind == Kind.NUMBER && number == null)
                || (kind == Kind.BOOLEAN && flag == null)) {
            throw new IllegalArgumentException("Query result payload does not match " + kind);
        }
    }

    public static ScriptQueryResult unhandled() {
        return new ScriptQueryResult(Kind.UNHANDLED, null, null, null);
    }

    public static ScriptQueryResult nil() {
        return new ScriptQueryResult(Kind.NIL, null, null, null);
    }

    public static ScriptQueryResult string(String value) {
        return new ScriptQueryResult(Kind.STRING, Objects.requireNonNull(value, "value"), null, null);
    }

    public static ScriptQueryResult number(double value) {
        return new ScriptQueryResult(Kind.NUMBER, null, value, null);
    }

    public static ScriptQueryResult bool(boolean value) {
        return new ScriptQueryResult(Kind.BOOLEAN, null, null, value);
    }

    public static ScriptQueryResult failed() {
        return new ScriptQueryResult(Kind.FAILED, null, null, null);
    }

    public boolean handled() {
        return kind != Kind.UNHANDLED;
    }

    public Optional<String> stringValue() {
        return Optional.ofNullable(text);
    }

    public OptionalDouble numberValue() {
        return number == null ? OptionalDouble.empty() : OptionalDouble.of(number);
    }

    public Optional<Boolean> booleanValue() {
        return Optional.ofNullable(flag);
    }
}
