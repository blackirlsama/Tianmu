package com.tianmu.agent.core;

/** Page selection is an input hint, never an authorization grant. IDs stay strings on the wire. */
public record RequestContext(String videoId) {
    public RequestContext {
        if (videoId == null || !videoId.matches("[1-9][0-9]{0,18}"))
            throw new AgentException("INVALID_REQUEST", "Invalid context videoId");
        try { Long.parseLong(videoId); }
        catch (NumberFormatException e) { throw new AgentException("INVALID_REQUEST", "Invalid context videoId"); }
    }
}
