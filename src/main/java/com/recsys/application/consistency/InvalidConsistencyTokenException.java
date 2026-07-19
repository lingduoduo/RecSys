package com.recsys.application.consistency;

public class InvalidConsistencyTokenException extends IllegalArgumentException {
    public InvalidConsistencyTokenException() { super("Invalid consistency token"); }
}
