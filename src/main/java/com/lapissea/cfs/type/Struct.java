package com.lapissea.cfs.type;

import com.lapissea.cfs.Index;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.field.FieldCompiler;
import com.lapissea.cfs.type.field.reflection.BitFieldMerger;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Struct<T extends IOInstance<T>>{
	
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
		
		synchronized(STRUCT_CACHE){
			//noinspection unchecked
			var cached=(Struct<T>)STRUCT_CACHE.get(instanceClass);
			if(cached!=null) return cached;
		}
		
		if(instanceClass==(Object)IOInstance.class) throw new IllegalArgumentException("Can not compile Instance itself");
		
		Thread thread=STRUCT_COMPILE.get(instanceClass);
		if(thread!=null){
			if(thread==Thread.currentThread()) throw new MalformedStructLayout("Recursive struct compilation");
			UtilL.sleepWhile(()->STRUCT_COMPILE.containsKey(instanceClass));
			return of(instanceClass);
		}
		
		try{
			STRUCT_COMPILE.put(instanceClass, Thread.currentThread());
			
			Struct<T> struct=new Struct<>(instanceClass);
			synchronized(STRUCT_CACHE){
				STRUCT_CACHE.put(instanceClass, struct);
			}
			
			LogUtil.println("COMPILED:", struct);
			return struct;
		}finally{
			STRUCT_COMPILE.remove(instanceClass);
		}
	}
	
	private final Class<T>     type;
	private final OptionalLong fixedSize;
	
	private final List<IOField<T, ?>> fields;
	private final List<IOField<T, ?>> ioFields;
	
	public Struct(Class<T> type){
		this.type=type;
		fixedSize=OptionalLong.empty();
		fields=computeFields();
		ioFields=mergeBitSpace(computeOrderIndex().mapData(fields));
		LogUtil.println(ioFields);
		LogUtil.println(ioFields.stream().map(i->i.getFixedSize()));
	}
	private List<IOField<T, ?>> mergeBitSpace(List<IOField<T, ?>> mapData){
		var result    =new LinkedList<IOField<T, ?>>();
		var bitBuilder=new LinkedList<IOField.Bit<T, ?>>();
		
		Runnable pushBuilt=()->{
			if(bitBuilder.isEmpty()) return;
			result.add(new BitFieldMerger<>(bitBuilder));
			bitBuilder.clear();
		};
		
		for(IOField<T, ?> field : mapData){
			if(field.getWordSpace()==WordSpace.BIT){
				bitBuilder.add((IOField.Bit<T, ?>)field);
				continue;
			}
			pushBuilt.run();
			result.add(field);
		}
		pushBuilt.run();
		
		return List.copyOf(result);
	}
	
	private Index computeOrderIndex(){
		try{
			return new DepSort<>(fields, f->f.getDeps().stream().mapToInt(IOField::getIndex))
				       .sort(Comparator.comparingInt((IOField<T, ?> f)->0)
				                       .thenComparingInt((IOField<T, ?> f)->f.getFixedSize().isPresent()?0:1)
				                       .thenComparingInt((IOField<T, ?> f)->f.getWordSpace().sortOrder)
				       );
		}catch(DepSort.CycleException e){
			throw new MalformedStructLayout("Field dependency cycle detected:\n"+
			                                e.cycle.mapData(fields)
			                                       .stream()
			                                       .map(IOField::getNameOrId)
			                                       .collect(Collectors.joining("\n")));
		}
	}
	
	private List<IOField<T, ?>> computeFields(){
		return FieldCompiler.create().compile(this);
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
	
	public List<IOField<T, ?>> getIoFields(){
		return ioFields;
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
