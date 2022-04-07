package com.lapissea.cfs.type;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.util.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NOT_NULL;

public sealed class Struct<T extends IOInstance<T>> implements RuntimeType<T>{
	
	public interface Pool<T extends IOInstance<T>>{
		
		class GeneralStructArray<T extends IOInstance<T>> implements Pool<T>{
			
			private final Struct<T>                          typ;
			private final VirtualFieldDefinition.StoragePool poolType;
			
			private final int poolSize;
			private final int primitiveMemorySize;
			
			private Object[] pool;
			private byte[]   primitives;
			
			public GeneralStructArray(Struct<T> typ, VirtualFieldDefinition.StoragePool pool){
				this.poolType=pool;
				this.typ=typ;
				
				int poolSize=0;
				if(typ.poolSizes!=null) poolSize=typ.poolSizes[pool.ordinal()];
				
				int primitiveMemorySize=0;
				if(typ.poolPrimitiveSizes!=null) primitiveMemorySize=typ.poolPrimitiveSizes[pool.ordinal()];
				
				assert poolSize>0||primitiveMemorySize>0;
				
				this.poolSize=poolSize;
				this.primitiveMemorySize=primitiveMemorySize;
			}
			
			private void protectAccessor(VirtualAccessor<T> accessor){
				if(accessor.getDeclaringStruct()!=typ){
					throw new IllegalArgumentException(accessor.getDeclaringStruct()+" != "+typ);
				}
			}
			
			private void protectAccessor(VirtualAccessor<T> accessor, List<Class<?>> types){
				protectAccessor(accessor);
				
				if(types.stream().noneMatch(type->accessor.getType()==type)){
					throw new IllegalArgumentException(accessor.getType()+" != "+types.stream().map(Class::getName).collect(Collectors.joining(" || ", "(", ")")));
				}
			}
			
			private int getPtrIndex(VirtualAccessor<T> accessor){
				if(DEBUG_VALIDATION) protectAccessor(accessor);
				
				int index=accessor.getPtrIndex();
				if(index==-1){
					if(accessor.getPrimitiveOffset()==-1) throw new IllegalStateException(TextUtil.toNamedJson(accessor));
					throw new ClassCastException(accessor+" is not of an object type!");
				}
				return index;
			}
			
			@Override
			public void set(VirtualAccessor<T> accessor, Object value){
				int index=getPtrIndex(accessor);
				if(pool==null) pool=new Object[poolSize];
				pool[index]=value;
				
			}
			@Override
			public Object get(VirtualAccessor<T> accessor){
				if(accessor.getPtrIndex()==-1){
					var typ=accessor.getType();
					if(typ==long.class) return getLong(accessor);
					if(typ==int.class) return getInt(accessor);
					throw new NotImplementedException(typ.getName());
				}
				int index=getPtrIndex(accessor);
				if(pool==null) return null;
				return pool[index];
			}
			
			
			@Override
			public long getLong(VirtualAccessor<T> accessor){
				if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(long.class, int.class));
				
				if(primitives==null) return 0;
				
				return switch(accessor.getPrimitiveSize()){
					case Long.BYTES -> MemPrimitive.getLong(primitives, accessor.getPrimitiveOffset());
					case Integer.BYTES -> MemPrimitive.getInt(primitives, accessor.getPrimitiveOffset());
					default -> throw new IllegalStateException();
				};
			}
			
			@Override
			public void setLong(VirtualAccessor<T> accessor, long value){
				if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(long.class));
				
				if(primitives==null){
					if(value==0) return;
					primitives=new byte[primitiveMemorySize];
				}
				MemPrimitive.setLong(primitives, accessor.getPrimitiveOffset(), value);
			}
			
			@Override
			public int getInt(VirtualAccessor<T> accessor){
				if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(int.class));
				
				if(primitives==null) return 0;
				return MemPrimitive.getInt(primitives, accessor.getPrimitiveOffset());
			}
			
			@Override
			public void setInt(VirtualAccessor<T> accessor, int value){
				if(DEBUG_VALIDATION) protectAccessor(accessor, List.of(int.class, long.class));
				
				if(primitives==null){
					if(value==0) return;
					primitives=new byte[primitiveMemorySize];
				}
				
				switch(accessor.getPrimitiveSize()){
					case Long.BYTES -> MemPrimitive.setLong(primitives, accessor.getPrimitiveOffset(), value);
					case Integer.BYTES -> MemPrimitive.setInt(primitives, accessor.getPrimitiveOffset(), value);
					default -> throw new IllegalStateException();
				}
			}
			
			
			@Override
			public String toString(){
				return typ.getType().getName()+
				       typ.getFields()
				          .stream()
				          .map(IOField::getAccessor)
				          .filter(f->f instanceof VirtualAccessor acc&&acc.getStoragePool()==poolType)
				          .map(f->(VirtualAccessor<T>)f)
				          .map(c->c.getName()+": "+Utils.toShortString(get(c)))
				          .collect(Collectors.joining(", ", "{", "}"));
			}
		}
		
		void set(VirtualAccessor<T> accessor, Object value);
		Object get(VirtualAccessor<T> accessor);
		
		long getLong(VirtualAccessor<T> accessor);
		void setLong(VirtualAccessor<T> accessor, long value);
		
		int getInt(VirtualAccessor<T> accessor);
		void setInt(VirtualAccessor<T> accessor, int value);
	}
	
	public static final class Unmanaged<T extends IOInstance.Unmanaged<T>> extends Struct<T>{
		
		public interface Constr<T>{
			T create(DataProvider provider, Reference reference, TypeLink type) throws IOException;
		}
		
		public static Unmanaged<?> thisClass(){
			return ofUnknown(getStack(s->s.skip(2)));
		}
		
		public static Unmanaged<?> ofUnknown(@NotNull Type instanceType){
			return ofUnknown(Utils.typeToRaw(instanceType));
		}
		
		@SuppressWarnings({"unchecked", "rawtypes"})
		public static Unmanaged<?> ofUnknown(@NotNull Class<?> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			if(!UtilL.instanceOf(instanceClass, IOInstance.class)){
				throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.class.getSimpleName());
			}
			
			return ofUnmanaged((Class<? extends IOInstance.Unmanaged>)instanceClass);
		}
		
		public static <T extends IOInstance.Unmanaged<T>> Unmanaged<T> ofUnmanaged(Class<T> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			Unmanaged<T> cached=(Unmanaged<T>)getCached(instanceClass);
			if(cached!=null) return cached;
			
			
			return compile(instanceClass, Unmanaged::new);
		}
		
		private       Constr<T>   unmanagedConstructor;
		private final boolean     overridingDynamicUnmanaged;
		private       FieldSet<T> unmanagedStaticFields;
		
		public Unmanaged(Class<T> type){
			super(type);
			overridingDynamicUnmanaged=checkOverridingUnmanaged();
		}
		
		private boolean checkOverridingUnmanaged(){
			Class<?> t=getType();
			while(true){
				try{
					return !t.getDeclaredMethod("listDynamicUnmanagedFields").getDeclaringClass().equals(IOInstance.Unmanaged.class);
				}catch(NoSuchMethodException e){
					t=t.getSuperclass();
				}
			}
		}
		
		public boolean isOverridingDynamicUnmanaged(){
			return overridingDynamicUnmanaged;
		}
		
		public Constr<T> requireUnmanagedConstructor(){
			if(unmanagedConstructor==null){
				unmanagedConstructor=Access.findConstructor(getType(), Constr.class, Access.getFunctionalMethod(Constr.class).getParameterTypes());
			}
			return unmanagedConstructor;
		}
		
		@Deprecated
		@Override
		public Supplier<T> requireEmptyConstructor(){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public String instanceToString(Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return instanceToString0(
				ioPool, instance, doShort, start, end, fieldValueSeparator, fieldSeparator,
				Stream.concat(getFields().stream().filter(f->f.getName().indexOf(IOFieldTools.GENERATED_FIELD_SEPARATOR)!=-1), instance.listUnmanagedFields())
			);
		}
		
		public FieldSet<T> getUnmanagedStaticFields(){
			if(unmanagedStaticFields==null){
				unmanagedStaticFields=FieldCompiler.create().compileStaticUnmanaged(this);
			}
			
			return unmanagedStaticFields;
		}
	}
	
	private static final Lock STRUCT_CACHE_LOCK=new ReentrantLock();
	
	private static final Map<Class<?>, Struct<?>> STRUCT_CACHE  =new WeakValueHashMap<>();
	private static final Map<Class<?>, Thread>    STRUCT_COMPILE=new ConcurrentHashMap<>();
	
	
	private static Class<?> getStack(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow().getDeclaringClass());
	}
	
	public static Struct<?> thisClass(){
		return ofUnknown(getStack(s->s.skip(2)));
	}
	
	public static Struct<?> ofUnknown(@NotNull Type instanceType){
		return ofUnknown(Utils.typeToRaw(instanceType));
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(!UtilL.instanceOf(instanceClass, IOInstance.class)){
			throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.class.getSimpleName());
		}
		
		return of((Class<? extends IOInstance>)instanceClass);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>> Struct<T> of(Class<T> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		Struct<T> cached=getCached(instanceClass);
		if(cached!=null) return cached;
		
		if(UtilL.instanceOf(instanceClass, IOInstance.Unmanaged.class)){
			return (Struct<T>)Unmanaged.ofUnknown(instanceClass);
		}
		return compile(instanceClass, t->{
			if(!UtilL.instanceOf(t, IOInstance.class)) throw new ClassCastException(t.getName()+" is not an "+IOInstance.class.getSimpleName());
			if(UtilL.instanceOf(t, IOInstance.Unmanaged.class)) throw new ClassCastException(t.getName()+" is unmanaged!");
			return new Struct<>(t);
		});
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>, S extends Struct<T>> S compile(Class<T> instanceClass, Function<Class<T>, S> newStruct){
		if(Modifier.isAbstract(instanceClass.getModifiers())){
			throw new IllegalArgumentException("Can not compile "+instanceClass.getName()+" because it is abstract");
		}
		
		Thread thread=STRUCT_COMPILE.get(instanceClass);
		if(thread!=null&&thread==Thread.currentThread()){
			throw new MalformedStructLayout("Recursive struct compilation");
		}
		
		S struct;
		try{
			STRUCT_CACHE_LOCK.lock();
			//If class was compiled in another thread this should early exit
			var existing=STRUCT_CACHE.get(instanceClass);
			if(existing!=null) return (S)existing;
			STRUCT_COMPILE.put(instanceClass, Thread.currentThread());
			
			struct=newStruct.apply(instanceClass);
			
			STRUCT_CACHE.put(instanceClass, struct);
		}catch(Throwable e){
			throw new MalformedStructLayout("Failed to compile "+instanceClass.getName(), e);
		}finally{
			STRUCT_COMPILE.remove(instanceClass);
			STRUCT_CACHE_LOCK.unlock();
		}
		
		if(GlobalConfig.PRINT_COMPILATION){
			LogUtil.println(ConsoleColors.GREEN_BRIGHT+TextUtil.toTable("Compiled: "+struct.getType().getName(), struct.getFields())+ConsoleColors.RESET);
		}
		return struct;
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> getCached(Class<T> instanceClass){
		try{
			STRUCT_CACHE_LOCK.lock();
			return (Struct<T>)STRUCT_CACHE.get(instanceClass);
		}finally{
			STRUCT_CACHE_LOCK.unlock();
		}
	}
	
	private final Class<T> type;
	
	private final FieldSet<T> fields;
	private final boolean[]   hasPools;
	private final int[]       poolSizes;
	private final int[]       poolPrimitiveSizes;
	
	private final boolean canHavePointers;
	
	private Supplier<T> emptyConstructor;
	
	private int invalidInitialNulls=-1;
	
	public Struct(Class<T> type){
		this.type=type;
		this.fields=FieldCompiler.create().compile(this);
		this.fields.forEach(IOField::init);
		poolSizes=calcPoolSizes();
		poolPrimitiveSizes=calcPoolPrimitiveSizes();
		hasPools=calcHasPools();
		canHavePointers=calcCanHavePointers();
	}
	
	private Stream<VirtualAccessor<T>> virtualAccessorStream(){
		return fields.stream()
		             .map(IOField::getAccessor)
		             .filter(f->f instanceof VirtualAccessor)
		             .map(c->((VirtualAccessor<T>)c));
	}
	
	private int[] calcPoolSizes(){
		var vPools=virtualAccessorStream().map(VirtualAccessor::getStoragePool).toList();
		if(vPools.isEmpty()) return null;
		var poolSizes=new int[VirtualFieldDefinition.StoragePool.values().length];
		for(var vPool : vPools){
			poolSizes[vPool.ordinal()]++;
		}
		return poolSizes;
	}
	
	private int[] calcPoolPrimitiveSizes(){
		var vPools=virtualAccessorStream().filter(a->a.getPrimitiveSize()>0).collect(Collectors.groupingBy(VirtualAccessor::getStoragePool));
		if(vPools.isEmpty()) return null;
		var poolSizes=new int[VirtualFieldDefinition.StoragePool.values().length];
		for(var e : vPools.entrySet()){
			poolSizes[e.getKey().ordinal()]=e.getValue()
			                                 .stream()
			                                 .mapToInt(a->a.getPrimitiveOffset()+a.getPrimitiveSize())
			                                 .max()
			                                 .orElseThrow();
		}
		return poolSizes;
	}
	
	private boolean[] calcHasPools(){
		if(poolSizes==null&&poolPrimitiveSizes==null){
			return null;
		}
		var b=new boolean[poolSizes==null?poolPrimitiveSizes.length:poolSizes.length];
		for(int i=0;i<b.length;i++){
			boolean p=poolSizes!=null&&poolSizes[i]>0;
			boolean n=poolPrimitiveSizes!=null&&poolPrimitiveSizes[i]>0;
			
			b[i]=p||n;
		}
		
		return b;
	}
	
	private boolean calcCanHavePointers(){
		if(this instanceof Struct.Unmanaged) return true;
		return fields.stream().anyMatch(f->{
			var acc=f.getAccessor();
			if(acc==null) return true;
			if(acc.hasAnnotation(IOType.Dynamic.class)) return true;
			if(f instanceof IOField.Ref) return true;
			if(acc.getType()==ChunkPointer.class) return true;
			if(acc.getType()==Reference.class) return true;
			if(IOInstance.isInstance(acc.getType())){
				if(!IOInstance.isManaged(acc.getType())) return true;
				var s=Struct.ofUnknown(acc.getType());
				if(s.getCanHavePointers()) return true;
			}
			return false;
		});
	}
	
	@Override
	public String toString(){
		return getType().getSimpleName()+"{}";
	}
	@Override
	public Class<T> getType(){
		return type;
	}
	
	public FieldSet<T> getFields(){
		return fields;
	}
	public IOField<T, ?> toIOField(Field field){
		if(field.getDeclaringClass()!=getType()) throw new IllegalArgumentException();
		return getFields().byName(field.getName()).orElseThrow();
	}
	@Override
	public boolean getCanHavePointers(){
		return canHavePointers;
	}
	
	public String instanceToString(Pool<T> ioPool, T instance, boolean doShort){
		return instanceToString(ioPool, instance, doShort, "{", "}", "=", ", ");
	}
	public String instanceToString(Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return instanceToString0(
			ioPool, instance, doShort, start, end, fieldValueSeparator, fieldSeparator,
			fields.stream().filter(f->f.getName().indexOf(IOFieldTools.GENERATED_FIELD_SEPARATOR)!=-1)
		);
	}
	
	protected String instanceToString0(Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator, Stream<IOField<T, ?>> fields){
		var prefix=start;
		
		if(!doShort){
			var simple=getType().getSimpleName();
			var index =simple.lastIndexOf('$');
			if(index!=-1) simple=simple.substring(index+1);
			
			prefix=simple+prefix;
		}
		
		StringJoiner joiner=new StringJoiner(fieldSeparator, prefix, end);
		
		var i=fields.iterator();
		while(i.hasNext()){
			var field=i.next();
			
			Optional<String> str;
			try{
				str=field.instanceToString(ioPool, instance, doShort||TextUtil.USE_SHORT_IN_COLLECTIONS);
			}catch(FieldIsNullException e){
				str=Optional.of("<UNINITIALIZED>");
			}
			
			if(str.isEmpty()) continue;
			
			StringJoiner f=new StringJoiner("");
			f.add(field.getName()).add(fieldValueSeparator).add(str.get());
			joiner.merge(f);
		}
		return joiner.toString();
	}
	
	@Override
	public Supplier<T> requireEmptyConstructor(){
		if(emptyConstructor==null) emptyConstructor=Access.findConstructor(getType(), Supplier.class);
		return emptyConstructor;
	}
	
	public boolean hasInvalidInitialNulls(){
		if(invalidInitialNulls==-1){
			boolean inv=false;
			if(fields.unpackedStream().anyMatch(f->f.getNullability()==NOT_NULL)){
				var obj =requireEmptyConstructor().get();
				var pool=allocVirtualVarPool(IO);
				inv=fields.unpackedStream()
				          .filter(f->f.getNullability()==NOT_NULL)
				          .anyMatch(f->f.isNull(pool, obj));
			}
			invalidInitialNulls=inv?1:0;
			return inv;
		}
		return invalidInitialNulls==1;
	}
	
	@Nullable
	public Pool<T> allocVirtualVarPool(VirtualFieldDefinition.StoragePool pool){
		if(hasPools==null||!hasPools[pool.ordinal()]) return null;
		return new Pool.GeneralStructArray<>(this, pool);
	}
	
	public GenericContext describeGenerics(TypeLink def){
		return new GenericContext.Deferred(()->{
			var parms=getClass().getTypeParameters();
			var types=IntStream.range(0, parms.length)
			                   .boxed()
			                   .collect(Collectors.toMap(i->parms[i].getName(), i->def.arg(i).generic(null)));
			return new GenericContext.MapConstant(types);
		});
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof Struct<?> that&&
		       equals(that);
	}
	
	public boolean equals(Struct<?> o){
		return this==o||
		       o!=null&&
		       type.equals(o.type);
	}
	
	@Override
	public int hashCode(){
		return type.getName().hashCode();
	}
}
