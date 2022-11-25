package com.lapissea.cfs.type;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.chunk.MemoryOperations;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.cfs.type.compilation.DefInstanceCompiler;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.TriFunction;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.StoragePool.INSTANCE;
import static com.lapissea.cfs.type.field.StoragePool.IO;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public sealed interface IOInstance<SELF extends IOInstance<SELF>> extends Cloneable, Stringify{
	
	/**
	 * <p>
	 * This interface is used to declare the object layout of a <i>managed</i> instance. This means any interface that extends
	 * this can be thought of as an equivalent to {@link Managed}. An implementation will be generated as needed.
	 * </p>
	 * <p>
	 * Any interface that extends this (directly or indirectly) must only contain getters and setters of fields. All "fields"
	 * are implicitly an IOField
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	non-sealed interface Def<SELF extends Def<SELF>> extends IOInstance<SELF>{
		
		@Retention(RetentionPolicy.RUNTIME)
		@Target({ElementType.TYPE})
		@interface Order{
			String[] value();
		}
		
		@Retention(RetentionPolicy.RUNTIME)
		@Target({ElementType.TYPE})
		@interface ToString{
			
			@Retention(RetentionPolicy.RUNTIME)
			@Target({ElementType.TYPE})
			@interface Format{
				String value();
			}
			
			boolean name() default true;
			boolean curly() default true;
			boolean fNames() default true;
			String[] filter() default {};
		}
		
		String IMPL_NAME_POSTFIX      ="€Impl";
		String IMPL_COMPLETION_POSTFIX="€Full";
		
		static <T extends Def<T>> NewObj<T> constrRef(Class<T> type){
			return Struct.of(type).emptyConstructor();
		}
		
		static <T extends Def<T>, A1> Function<A1, T> constrRef(Class<T> type, Class<A1> arg1Type){
			record Sig(Class<?> c, Class<?> arg){}
			class Cache{
				static final Map<Sig, Function<?, ?>> CH=new ConcurrentHashMap<>();
			}
			if(isDefinition(type)) type=DefInstanceCompiler.getImpl(type, false);
			
			//noinspection unchecked
			return (Function<A1, T>)Cache.CH.computeIfAbsent(new Sig(type, arg1Type), t->{
				try{
					var ctor=t.c.getConstructor(t.arg);
					return Access.makeLambda(ctor, Function.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>, A1, A2> BiFunction<A1, A2, T> constrRef(Class<T> type, Class<A1> arg1Type, Class<A1> arg2Type){
			record Sig(Class<?> c, Class<?> arg1, Class<?> arg2){}
			class Cache{
				static final Map<Sig, BiFunction<?, ?, ?>> CH=new ConcurrentHashMap<>();
			}
			if(isDefinition(type)) type=DefInstanceCompiler.getImpl(type, false);
			
			//noinspection unchecked
			return (BiFunction<A1, A2, T>)Cache.CH.computeIfAbsent(new Sig(type, arg1Type, arg2Type), t->{
				try{
					var ctor=t.c.getConstructor(t.arg1, t.arg2);
					return Access.makeLambda(ctor, BiFunction.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>, A1, A2, A3> TriFunction<A1, A2, A3, T> constrRef(Class<T> type, Class<A1> arg1Type, Class<A1> arg2Type, Class<A1> arg3Type){
			record Sig(Class<?> c, Class<?> arg1, Class<?> arg2, Class<?> arg3){}
			class Cache{
				static final Map<Sig, TriFunction<?, ?, ?, ?>> CH=new ConcurrentHashMap<>();
			}
			if(isDefinition(type)) type=DefInstanceCompiler.getImpl(type, false);
			
			//noinspection unchecked
			return (TriFunction<A1, A2, A3, T>)Cache.CH.computeIfAbsent(new Sig(type, arg1Type, arg2Type, arg3Type), t->{
				try{
					var ctor=t.c.getConstructor(t.arg1, t.arg2, t.arg3);
					return Access.makeLambda(ctor, TriFunction.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>> MethodHandle constrRef(Class<T> type, Class<?>... argTypes){
			record Sig(Class<?> c, Class<?>[] args){}
			class Cache{
				static final Map<Sig, MethodHandle> CH=new ConcurrentHashMap<>();
			}
			if(isDefinition(type)) type=DefInstanceCompiler.getImpl(type, false);
			
			return Cache.CH.computeIfAbsent(new Sig(type, argTypes.clone()), t->{
				try{
					var ctor=t.c.getConstructor(t.args);
					return Access.makeMethodHandle(ctor);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>> T of(Class<T> clazz){
			return Struct.of(clazz).make();
		}
		
		static <T extends Def<T>> T of(Class<T> clazz, int arg1){
			var ctr=DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invokeExact(arg1);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, long arg1){
			var ctr=DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invokeExact(arg1);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, Object arg1){
			var ctr=DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invoke(arg1);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, Object arg1, Object arg2){
			var ctr=DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invoke(arg1, arg2);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, Object... args){
			var ctr=DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invokeWithArguments(args);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		
		private static RuntimeException failNew(Class<?> clazz, Throwable e){
			throw new RuntimeException("Failed to instantiate "+clazz.getName(), e);
		}
		
		@SuppressWarnings("rawtypes")
		static <T extends IOInstance.Def<T>> Optional<Class<T>> unmap(Class<? extends Def> impl){
			return DefInstanceCompiler.unmap(impl);
		}
		static boolean isDefinition(Class<?> c){
			return c.isInterface()&&UtilL.instanceOf(c, IOInstance.Def.class);
		}
		static <T extends Def<T>, A1> MethodHandle dataConstructor(Class<T> type){
			return DefInstanceCompiler.dataConstructor(type);
		}
		
		static <T extends Def<T>> Class<T> partialImplementation(Class<T> type, Set<String> includedFieldNames){
			return DefInstanceCompiler.getImplPartial(new DefInstanceCompiler.Key<>(type, Optional.of(includedFieldNames)));
		}
	}
	
	abstract non-sealed class Managed<SELF extends Managed<SELF>> implements IOInstance<SELF>{
		
		private Struct<SELF>  thisStruct;
		private VarPool<SELF> virtualFields;
		
		public Managed(){}
		
		public Managed(Struct<SELF> thisStruct){
			if(DEBUG_VALIDATION) checkStruct(thisStruct);
			this.thisStruct=thisStruct;
			virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
		}
		
		private void checkStruct(Struct<SELF> thisStruct){
			if(!thisStruct.getConcreteType().equals(getClass())){
				throw new IllegalArgumentException(thisStruct+" is not "+getClass());
			}
		}
		
		@SuppressWarnings("unchecked")
		private void init(){
			thisStruct=Struct.of((Class<SELF>)getClass());
			virtualFields=getThisStruct().allocVirtualVarPool(INSTANCE);
		}
		
		@Override
		public Struct<SELF> getThisStruct(){
			if(thisStruct==null) init();
			return thisStruct;
		}
		
		private VarPool<SELF> getVirtualPool(){
			if(thisStruct==null) init();
			return virtualFields;
		}
		
		@SuppressWarnings("unchecked")
		protected final SELF self(){return (SELF)this;}
		
		@Override
		public String toString(){
			return getThisStruct().instanceToString(self(), false);
		}
		@Override
		public String toShortString(){
			return getThisStruct().instanceToString(self(), true);
		}
		
		@Override
		@SuppressWarnings("unchecked")
		public boolean equals(Object o){
			if(this==o) return true;
			
			if(!(o instanceof IOInstance<?> that)) return false;
			var struct=getThisStruct();
			if(that.getThisStruct()!=struct) return false;
			
			for(var field : struct.getInstanceFields()){
				if(!field.instancesEqual(null, self(), null, (SELF)that)) return false;
			}
			
			return true;
		}
		
		@Override
		public int hashCode(){
			int result=1;
			for(var field : getThisStruct().getInstanceFields()){
				result=31*result+field.instanceHashCode(null, self());
			}
			return result;
		}
		
		@Override
		public void allocateNulls(DataProvider provider) throws IOException{
			for(var ref : getThisStruct().getInstanceFields().onlyRefs()){
				if(!ref.isNull(null, self()))
					continue;
				ref.allocate(self(), provider, getGenericContext());
			}
		}
		
		private GenericContext getGenericContext(){
			//TODO: find generic context?
			return null;
		}
		
		
		@SuppressWarnings("unchecked")
		@Override
		public SELF clone(){
			SELF c;
			try{
				c=(SELF)super.clone();
			}catch(CloneNotSupportedException e){
				throw new RuntimeException(e);
			}
			
			for(IOField<SELF, ?> field : getThisStruct().getFields()){
				if(field.typeFlag(IOField.PRIMITIVE_OR_ENUM_FLAG)){
					continue;
				}
				if(Utils.isVirtual(field, IO)){
					continue;
				}
				var acc=field.getAccessor();
				var typ=acc.getType();
				if(typ.isArray()){
					var arrField=(IOField<SELF, Object[]>)field;
					
					var arr=arrField.get(null, (SELF)this);
					if(arr==null) continue;
					
					arr=arr.clone();
					
					if(isInstance(typ.componentType())){
						var iArr=(IOInstance<?>[])arr;
						for(int i=0;i<iArr.length;i++){
							var el=iArr[i];
							iArr[i]=el.clone();
						}
					}
					
					arrField.set(null, c, arr);
					continue;
				}
				
				if(!isInstance(typ)) continue;
				if(isUnmanaged(typ)) continue;
				var instField=(IOField<SELF, IOInstance<?>>)field;
				
				var val=instField.get(null, (SELF)this);
				if(val==null) continue;
				
				val=val.clone();
				
				instField.set(null, c, val);
			}
			
			return c;
		}
	}
	
	abstract class Unmanaged<SELF extends Unmanaged<SELF>> extends IOInstance.Managed<SELF> implements DataProvider.Holder{
		
		private final DataProvider   provider;
		private       Reference      reference;
		private final TypeLink       typeDef;
		private       GenericContext genericCtx;
		
		private StructPipe<SELF> pipe;
		
		protected final boolean readOnly;
		
		protected Unmanaged(DataProvider provider, Reference reference, TypeLink typeDef, TypeLink.Check check){
			this(provider, reference, typeDef);
			check.ensureValid(typeDef, provider.getTypeDb());
		}
		
		public Unmanaged(DataProvider provider, Reference reference, TypeLink typeDef){
			this.provider=Objects.requireNonNull(provider);
			this.reference=reference.requireNonNull();
			this.typeDef=typeDef;
			readOnly=getDataProvider().isReadOnly();
		}
		
		@NotNull
		protected Stream<IOField<SELF, ?>> listDynamicUnmanagedFields(){
			return Stream.of();
		}
		
		@NotNull
		public final Stream<IOField<SELF, ?>> listUnmanagedFields(){
			var s =getThisStruct();
			var fs=s.getUnmanagedStaticFields().stream();
			if(!s.isOverridingDynamicUnmanaged()){
				return fs;
			}
			return Stream.concat(listDynamicUnmanagedFields(), fs);
		}
		
		public CommandSet.CmdReader getUnmanagedReferenceWalkCommands(){
			if(getThisStruct().isOverridingDynamicUnmanaged()){
				throw new NotImplementedException(getThisStruct()+" has dynamic fields! Please implement walk commands!");
			}else{
				throw new UnsupportedOperationException();
			}
		}
		
		public final TypeLink getTypeDef(){
			return typeDef;
		}
		
		
		public final GenericContext getGenerics(){
			if(genericCtx==null){
				genericCtx=getThisStruct().describeGenerics(typeDef);
			}else if(genericCtx instanceof GenericContext.Deferred d&&d.actualData()!=null){
				genericCtx=d.actualData();
			}
			return genericCtx;
		}
		
		@Override
		public final DataProvider getDataProvider(){
			return provider;
		}
		
		protected final boolean isSelfDataEmpty() throws IOException{
			try(var io=selfIO()){
				return io.getSize()==0;
			}
		}
		
		public final void notifyReferenceMovement(Reference newRef){
			newRef.requireNonNull();
			
			if(DEBUG_VALIDATION) ensureDataIntegrity(newRef);
			
			reference=newRef;
		}
		
		private void ensureDataIntegrity(Reference newRef){
			byte[] oldData, newData;
			try(var oldIo=reference.io(this);
			    var newIo=newRef.io(this)
			){
				oldData=oldIo.readRemaining();
				newData=newIo.readRemaining();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			if(!Arrays.equals(oldData, newData)){
				throw new IllegalStateException(
					"Data changed when moving reference! This is invalid behaviour\n"+
					Arrays.toString(oldData)+"\n"+
					Arrays.toString(newData)
				);
			}
		}
		
		@Override
		public Struct.Unmanaged<SELF> getThisStruct(){
			return (Struct.Unmanaged<SELF>)super.getThisStruct();
		}
		
		private boolean freed;
		
		public boolean isFreed(){
			return freed;
		}
		protected void notifyFreed(){
			freed=true;
		}
		
		public void free() throws IOException{
			MemoryOperations.freeSelfAndReferenced(self());
			notifyFreed();
		}
		
		protected final RandomIO selfIO() throws IOException{
			return reference.io(provider);
		}
		
		protected StructPipe<SELF> newPipe(){
			return StandardStructPipe.of(getThisStruct());
		}
		
		public final StructPipe<SELF> getPipe(){
			if(pipe==null) pipe=newPipe();
			return pipe;
		}
		
		@SuppressWarnings("unchecked")
		public <VT extends IOInstance<VT>> StructPipe<VT> getFieldPipe(IOField<SELF, VT> unmanagedField, VT fieldValue){
			Struct<VT> struct;
			if(unmanagedField.typeFlag(IOField.DYNAMIC_FLAG)){
				struct=fieldValue.getThisStruct();
			}else{
				struct=Struct.of((Class<VT>)unmanagedField.getType());
			}
			
			return StructPipe.of(pipe.getClass(), struct);
		}
		
		protected final void writeManagedFields() throws IOException{
			if(readOnly){
				throw new UnsupportedOperationException();
			}
			try(var io=selfIO()){
				getPipe().write(provider, io, self());
			}
		}
		protected final void readManagedFields() throws IOException{
			try(var io=selfIO()){
				getPipe().read(provider, io, self(), getGenerics());
			}
		}
		
		protected final void readManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io=getReference().io(this)){
				var pip=getPipe();
				pip.readDeps(pip.makeIOPool(), provider, io, pip.getFieldDependency().getDeps(field), self(), getGenerics());
			}
		}
		
		protected final void writeManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io=getReference().io(this)){
				getPipe().writeSingleField(provider, io, field, self());
			}
		}
		
		protected final long calcInstanceSize(WordSpace wordSpace){
			var pip=getPipe();
			var siz=pip.getSizeDescriptor();
			var f  =siz.getFixed(wordSpace);
			if(f.isPresent()) return f.getAsLong();
			return siz.calcUnknown(pip.makeIOPool(), getDataProvider(), self(), wordSpace);
		}
		
		public final Reference getReference(){
			return reference;
		}
		
		protected final void allocateNulls() throws IOException{
			allocateNulls(getDataProvider());
		}
		
		@Override
		public SELF clone() throws UnsupportedOperationException{
			throw new UnsupportedOperationException("Unmanaged objects can not be cloned");
		}
	}
	
	
	Struct<SELF> getThisStruct();
	
	void allocateNulls(DataProvider provider) throws IOException;
	
	@Override
	default String toShortString(){
		return getThisStruct().instanceToString(self(), true);
	}
	
	default String toString(boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return getThisStruct().instanceToString(self(), doShort, start, end, fieldValueSeparator, fieldSeparator);
	}
	
	SELF clone();
	
	@SuppressWarnings("unchecked")
	private SELF self(){return (SELF)this;}
	
	static boolean isInstance(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.class);
	}
	
	static boolean isUnmanaged(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.Unmanaged.class);
	}
	static boolean isManaged(Class<?> type){
		var isInstance =isInstance(type);
		var isUnmanaged=UtilL.instanceOf(type, IOInstance.Unmanaged.class);
		return isInstance&&!isUnmanaged;
	}
}
