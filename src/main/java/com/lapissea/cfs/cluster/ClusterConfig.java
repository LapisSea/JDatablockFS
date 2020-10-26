package com.lapissea.cfs.cluster;

import static com.lapissea.cfs.GlobalConfig.*;

public record ClusterConfig(
	boolean clearFreeData,
	boolean logActions
){
	public static final ClusterConfig DEFAULT=new ClusterConfig(true, DEBUG_VALIDATION);
}
