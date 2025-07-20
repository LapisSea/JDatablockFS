package com.lapissea.fuzz;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

//From https://stackoverflow.com/a/38658066/4464957
class NanoClock extends Clock{
	
	public static final NanoClock INSTANCE = new NanoClock();
	
	public static Instant now(){
		return INSTANCE.instant();
	}
	
	private final Clock   clock;
	private final long    initialNanos;
	private final Instant initialInstant;
	
	public NanoClock(){
		this(Clock.systemUTC());
	}
	
	public NanoClock(final Clock clock){
		this.clock = clock;
		initialInstant = clock.instant();
		initialNanos = System.nanoTime();
	}
	
	@Override
	public ZoneId getZone(){
		return clock.getZone();
	}
	
	@Override
	public Instant instant(){
		return initialInstant.plusNanos(System.nanoTime() - initialNanos);
	}
	
	@Override
	public Clock withZone(final ZoneId zone){
		return new NanoClock(clock.withZone(zone));
	}
	
}
