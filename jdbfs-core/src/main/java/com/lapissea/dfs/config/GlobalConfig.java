package com.lapissea.dfs.config;

public final class GlobalConfig{
	
	public static final boolean DEBUG_VALIDATION = ConfigDefs.deb();
	
	public static final boolean RELEASE_MODE       = ConfigDefs.RELEASE_MODE.resolveValLocking();
	public static final boolean TYPE_VALIDATION    = ConfigDefs.TYPE_VALIDATION.resolveValLocking();
	public static final boolean COSTLY_STACK_TRACE = ConfigDefs.COSTLY_STACK_TRACE.resolveValLocking();
	public static final int     BATCH_BYTES        = ConfigDefs.BATCH_BYTES.resolveValLocking();
	
}
