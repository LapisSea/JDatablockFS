package com.lapissea.cfs.cluster;

import java.time.Duration;
import java.util.Objects;

public record PackingConfig(
	Duration autoPackTime,
	float freeSpaceRatioTrigger,
	int freeChunkCountTrigger
){
	public PackingConfig{
		Objects.requireNonNull(autoPackTime);
		if(freeChunkCountTrigger<=0) throw new IllegalArgumentException(freeChunkCountTrigger+" must be positive");
		if(freeSpaceRatioTrigger<=0||freeSpaceRatioTrigger>=1) throw new IllegalArgumentException("Ratio "+freeChunkCountTrigger+" must be between 0 and 1");
	}
	
	public static final PackingConfig DEFAULT=new PackingConfig(Duration.ofMillis(50), 0.5F, 5);
}
