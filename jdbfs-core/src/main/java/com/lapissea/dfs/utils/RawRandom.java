package com.lapissea.dfs.utils;

import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * Version of random that is the same logic as {@link java.util.Random} except it has no synchronization or gaussian
 */
public final class RawRandom implements RandomGenerator{
	
	private static final long MULTIPLIER = 0x5DEECE66DL;
	private static final long ADDEND     = 0xBL;
	private static final long MASK       = (1L<<48) - 1;
	
	private static final AtomicLong SEED_UNIQUIFIER = new AtomicLong(8682522807148012L);
	private static long seedUniquifier(){
		// L'Ecuyer, "Tables of Linear Congruential Generators of Different Sizes and Good Lattice Structure", 1999
		while(true){
			long current = SEED_UNIQUIFIER.get(), next = current*1181783497276652981L;
			if(SEED_UNIQUIFIER.compareAndSet(current, next)) return next;
		}
	}
	
	public RawRandom(){ this(seedUniquifier()^System.nanoTime()); }
	public RawRandom(long seed){
		this.seed = initialScramble(seed);
	}
	
	private static long initialScramble(long seed){
		return (seed^MULTIPLIER)&MASK;
	}
	
	private long seed;
	
	private int next(int bits){
		var s = seed = (seed*MULTIPLIER + ADDEND)&MASK;
		return (int)(s >>> (48 - bits));
	}
	
	@Override
	public void nextBytes(byte[] bytes){
		for(int i = 0, len = bytes.length; i<len; ){
			for(int rnd = nextInt(), n = Math.min(len - i, Integer.SIZE/Byte.SIZE);
			    n-->0; rnd >>= Byte.SIZE){
				bytes[i++] = (byte)rnd;
			}
		}
	}
	public byte[] nextBytes(int length){
		var data = new byte[length];
		nextBytes(data);
		return data;
	}
	
	@Override
	public long nextLong(){
		return ((long)(next(32))<<32) + next(32);
	}
	@Override
	public int nextInt(){
		return next(32);
	}
	@Override
	public int nextInt(int bound){
		if(bound<=0) throw new IllegalArgumentException("bound<=0");
		int r = next(31);
		int m = bound - 1;
		if((bound&m) == 0){
			r = (int)((bound*(long)r)>>31);
		}else{
			for(int u = r; u - (r = u%bound) + m<0; u = next(31)) ;
		}
		return r;
	}
	@Override
	public boolean nextBoolean(){
		return next(1) != 0;
	}
	
	private static final double DOUBLE_UNIT = 0x1.0p-53; // 1.0 / (1L << Double.PRECISION)
	private static final float  FLOAT_UNIT  = 0x1.0p-24f; // 1.0f / (1 << Float.PRECISION)
	@Override
	public float nextFloat(){
		return next(Float.PRECISION)*FLOAT_UNIT;
	}
	@Override
	public double nextDouble(){
		return (((long)(next(Double.PRECISION - 27))<<27) + next(27))*DOUBLE_UNIT;
	}
}
