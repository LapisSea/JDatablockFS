package com.lapissea.cfs.type;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.*;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

public class Struct<T extends IOInstance<T>>{
	
	static class AutoLock implements AutoCloseable{
		private final Lock lock;
		AutoLock(Lock lock){this.lock=lock;}
		
		AutoLock doLock(){
			lock.lock();
			return this;
		}
		@Override
		public void close(){
			lock.unlock();
		}
	}
	
	private static final AutoLock STRUCT_CACHE_LOCK=new AutoLock(new ReentrantLock());
	
	private static final Map<Class<?>, Struct<?>> STRUCT_CACHE  =new WeakValueHashMap<>();
	private static final Map<Class<?>, Thread>    STRUCT_COMPILE=Collections.synchronizedMap(new HashMap<>());
	
	
	private static Class<?> getStack(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow().getDeclaringClass());
	}
	
	public static Struct<?> thisClass(){
		return ofUnknown(getStack(s->s.skip(1)));
	}
	
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(!UtilL.instanceOf(instanceClass, IOInstance.class)){
			throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.class.getSimpleName());
		}
		
		//noinspection unchecked
		return of((Class<? extends IOInstance>)instanceClass);
	}
	
	public static <T extends IOInstance<T>> Struct<T> of(Class<T> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		try(var ignored=STRUCT_CACHE_LOCK.doLock()){
			//noinspection unchecked
			var cached=(Struct<T>)STRUCT_CACHE.get(instanceClass);
			if(cached!=null) return cached;
			
		}
		
		
		if(instanceClass.equals(IOInstance.class)) throw new IllegalArgumentException("Can not compile Instance itself");
		
		Thread thread=STRUCT_COMPILE.get(instanceClass);
		if(thread!=null){
			if(thread==Thread.currentThread()) throw new MalformedStructLayout("Recursive struct compilation");
			UtilL.sleepWhile(()->STRUCT_COMPILE.containsKey(instanceClass));
			return of(instanceClass);
		}
		
		try(var ignored=STRUCT_CACHE_LOCK.doLock()){
			
			STRUCT_COMPILE.put(instanceClass, Thread.currentThread());
			
			Struct<T> struct=new Struct<>(instanceClass);
			STRUCT_CACHE.put(instanceClass, struct);
			
			if(GlobalConfig.PRINT_COMPILATION){
				LogUtil.println(TextUtil.toTable("Compiled:"+struct.getType().getSimpleName(), struct.getFields()));
			}
			
			return struct;
		}finally{
			STRUCT_COMPILE.remove(instanceClass);
		}
	}
	
	private final Class<T>     type;
	private final OptionalLong fixedSize;
	
	private final List<IOField<T, ?>> fields;
	private final List<IOField<T, ?>> virtualFields;
	
	public Struct(Class<T> type){
		this.type=type;
		fixedSize=OptionalLong.empty();
		fields=FieldCompiler.create().compile(this);
		fields.forEach(IOField::init);
		virtualFields=fields.stream().filter(f->f.getAccessor() instanceof VirtualAccessor).toList();
	}
	
	@Override
	public String toString(){
		return getType().getSimpleName()+"{}";
	}
	public Class<T> getType(){
		return type;
	}
	public OptionalLong getFixedSize(){
		return fixedSize;
	}
	
	public List<IOField<T, ?>> getFields(){
		return fields;
	}
	public List<IOField<T, ?>> getVirtualFields(){
		return virtualFields;
	}
	
	public Optional<IOField<T, ?>> getFieldByName(String name){
		return getFields().stream().filter(f->f.getName().equals(name)).findAny();
	}
	public <E> Stream<IOField<T, E>> getFieldsByType(Class<E> type){
		return getFields().stream().filter(f->UtilL.instanceOf(f.getAccessor().getType(), type)).map(f->(IOField<T, E>)f);
	}
	
	public String instanceToString(T instance, boolean doShort){
		StringBuilder sb=new StringBuilder();
		if(!doShort) sb.append(type.getSimpleName());
		sb.append('{');
		boolean comma=false;
		for(var field : fields){
			var str=field.instanceToString(instance, doShort);
			if(str==null) continue;
			
			if(comma) sb.append(", ");
			
			sb.append(field.getName()).append("=").append(str);
			comma=true;
		}
		return sb.append('}').toString();
	}
}
