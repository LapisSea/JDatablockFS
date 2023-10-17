package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@Warmup(iterations = 10, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(5)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class FieldAccessorBenchmark{

//	public static void main(String[] args){
//		var    f     = new FieldAccessorBenchmark();
//		var    dummy = new Dummy();
//		var    start = Instant.now();
//		var    b     = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
//		Object obj   = null;
//		for(long i = 0; i<10000000000L; i++){
//			dummy.setUp();
//			obj = f.obj(dummy, b);
//		}
//		var end = Instant.now();
//		LogUtil.println(obj, Duration.between(start, end).toMillis());
//	}
	
	@IOValue
	@State(Scope.Thread)
	public static class Dummy extends IOInstance.Managed<Dummy>{
		@IOValue.Generic
		Object obj = new Object();
		long  l;
		int   i;
		short s;
		
		@IOValue
		public Object getObj(){ return obj; }
		@IOValue
		public long getL(){ return l; }
		@IOValue
		public int getI(){ return i; }
		@IOValue
		public short getS(){ return s; }
		
		@Setup(Level.Invocation)
		public void setUp(){
			l++;
			i++;
			s++;
		}
	}
	
	private static final Struct<Dummy>        STRUCT = Struct.of(Dummy.class, Struct.STATE_DONE);
	private static final FieldAccessor<Dummy> obj    = STRUCT.getFields().requireExact(Object.class, "obj").getAccessor();
	private static final FieldAccessor<Dummy> l      = STRUCT.getFields().requireExact(long.class, "l").getAccessor();
	private static final FieldAccessor<Dummy> i      = STRUCT.getFields().requireExact(int.class, "i").getAccessor();
	private static final FieldAccessor<Dummy> s      = STRUCT.getFields().requireExact(short.class, "s").getAccessor();
	
	@Benchmark
	public Object obj(Dummy dummy){
		return obj.get(null, dummy);
	}
	@Benchmark
	public long l(Dummy dummy){
		return l.getLong(null, dummy);
	}
	@Benchmark
	public int i(Dummy dummy){
		return i.getInt(null, dummy);
	}
	@Benchmark
	public short s(Dummy dummy){
		return s.getShort(null, dummy);
	}
}
