/**
 * Version-independent contracts for native Mind programs, bodies and host integration.
 *
 * <p>Mind behaviors control native movement through exclusive leases. The ACTION lease permits a
 * behavior to submit a bounded {@link com.magmaguy.magmacore.ai.MindActionRequest} to the host's
 * synchronous {@link com.magmaguy.magmacore.ai.MindActionSink}. MagmaCore validates and dispatches
 * requests but does not interpret, persist or queue them.</p>
 */
package com.magmaguy.magmacore.ai;
