package com.lapissea.dfs.type;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.core.memory.MemoryOperations;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.internal.AccessProvider;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.type.compilation.DefInstanceCompiler;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.TriFunction;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.regex.Pattern;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.StoragePool.INSTANCE;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public sealed interface IOInstance<SELF extends IOInstance<SELF>> extends Cloneable, Stringify{
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface StrFormat{
		
		@Retention(RetentionPolicy.RUNTIME)
		@Target({ElementType.TYPE})
		@interface Custom{
			String value();
		}
		
		boolean name() default true;
		boolean curly() default true;
		boolean fNames() default true;
		String[] filter() default {};
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface Order{
		String[] value();
	}
	
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
		
		String IMPL_NAME_POSTFIX       = "€Impl";
		String IMPL_COMPLETION_POSTFIX = "€Full";
		String IMPL_FIELDS_MARK        = "€€fields~";
		
		Pattern IMPL_PATTERN_CHECK = Pattern.compile(IMPL_NAME_POSTFIX + "\\d*(" + IMPL_FIELDS_MARK + "\\w+)?$");
		
		static <T extends Def<T>> NewObj<T> constrRef(Class<T> type){
			return Struct.of(type).emptyConstructor();
		}
		
		static <T extends Def<T>> IntFunction<T> constrRefI(Class<T> type){
			final class Cache{
				static final Map<Class<?>, IntFunction<?>> CH = new ConcurrentHashMap<>();
			}
			
			//noinspection unchecked
			return (IntFunction<T>)Cache.CH.computeIfAbsent(ensureConcrete(type), t -> {
				try{
					var ctor = t.getConstructor(int.class);
					return Access.makeLambda(ctor, IntFunction.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		static <T extends Def<T>> LongFunction<T> constrRefL(Class<T> type){
			final class Cache{
				static final Map<Class<?>, LongFunction<?>> CH = new ConcurrentHashMap<>();
			}
			
			//noinspection unchecked
			return (LongFunction<T>)Cache.CH.computeIfAbsent(ensureConcrete(type), t -> {
				try{
					var ctor = t.getConstructor(long.class);
					return Access.makeLambda(ctor, LongFunction.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>, A1> Function<A1, T> constrRef(Class<T> type, Class<A1> arg1Type){
			record Sig(Class<?> c, Class<?> arg){ }
			final class Cache{
				static final Map<Sig, Function<?, ?>> CH = new ConcurrentHashMap<>();
			}
			
			//noinspection unchecked
			return (Function<A1, T>)Cache.CH.computeIfAbsent(new Sig(ensureConcrete(type), arg1Type), t -> {
				try{
					var ctor = t.c.getConstructor(t.arg);
					return Access.makeLambda(ctor, Function.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>, A1, A2> BiFunction<A1, A2, T> constrRef(Class<T> type, Class<A1> arg1Type, Class<A1> arg2Type){
			record Sig(Class<?> c, Class<?> arg1, Class<?> arg2){ }
			final class Cache{
				static final Map<Sig, BiFunction<?, ?, ?>> CH = new ConcurrentHashMap<>();
			}
			//noinspection unchecked
			return (BiFunction<A1, A2, T>)Cache.CH.computeIfAbsent(new Sig(ensureConcrete(type), arg1Type, arg2Type), t -> {
				try{
					var ctor = t.c.getConstructor(t.arg1, t.arg2);
					return Access.makeLambda(ctor, BiFunction.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>, A1, A2, A3> TriFunction<A1, A2, A3, T> constrRef(Class<T> type, Class<A1> arg1Type, Class<A1> arg2Type, Class<A1> arg3Type){
			record Sig(Class<?> c, Class<?> arg1, Class<?> arg2, Class<?> arg3){ }
			final class Cache{
				static final Map<Sig, TriFunction<?, ?, ?, ?>> CH = new ConcurrentHashMap<>();
			}
			//noinspection unchecked
			return (TriFunction<A1, A2, A3, T>)Cache.CH.computeIfAbsent(new Sig(ensureConcrete(type), arg1Type, arg2Type, arg3Type), t -> {
				try{
					var ctor = t.c.getConstructor(t.arg1, t.arg2, t.arg3);
					return Access.makeLambda(ctor, TriFunction.class);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		static <T extends Def<T>> MethodHandle constrRef(Class<T> type, Class<?>... argTypes){
			record Sig(Class<?> c, Class<?>[] args){ }
			final class Cache{
				static final Map<Sig, MethodHandle> CH = new ConcurrentHashMap<>();
			}
			return Cache.CH.computeIfAbsent(new Sig(ensureConcrete(type), argTypes.clone()), t -> {
				try{
					var ctor = t.c.getConstructor(t.args);
					return Access.makeMethodHandle(ctor);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			});
		}
		
		private static <T extends Def<T>> Class<T> ensureConcrete(Class<T> type){
			if(!isDefinition(type)) return type;
			return DefInstanceCompiler.getImpl(type);
		}
		
		static <T extends Def<T>> T of(Class<T> clazz){
			return Struct.of(clazz, Struct.STATE_CONCRETE_TYPE).make();
		}
		
		static <T extends Def<T>> T of(Class<T> clazz, int arg1){
			var ctr = DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invoke(arg1);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, long arg1){
			var ctr = DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invoke(arg1);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, Object arg1){
			var ctr = DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invoke(arg1);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, Object arg1, Object arg2){
			var ctr = DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invoke(arg1, arg2);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		static <T extends Def<T>> T of(Class<T> clazz, Object... args){
			var ctr = DefInstanceCompiler.dataConstructor(clazz);
			try{
				return (T)ctr.invokeWithArguments(args);
			}catch(Throwable e){
				throw failNew(clazz, e);
			}
		}
		
		private static RuntimeException failNew(Class<?> clazz, Throwable e){
			if(e instanceof RuntimeException re){
				re.addSuppressed(new RuntimeException("Failed to instantiate " + clazz.getName()));
				throw re;
			}
			throw new RuntimeException("Failed to instantiate " + clazz.getName(), e);
		}
		
		@SuppressWarnings("rawtypes")
		static <T extends IOInstance.Def<T>> Optional<Class<T>> unmap(Class<? extends Def> impl){
			return DefInstanceCompiler.unmap(impl);
		}
		static boolean isDefinition(Class<?> c){
			return c.isInterface() && UtilL.instanceOf(c, IOInstance.Def.class);
		}
		static boolean isDefinitionImplementation(Class<?> c){
			return !c.isInterface() && UtilL.instanceOf(c, IOInstance.Def.class) && IMPL_PATTERN_CHECK.matcher(c.getName()).find();
		}
		static <T extends Def<T>, A1> MethodHandle dataConstructor(Class<T> type){
			return DefInstanceCompiler.dataConstructor(type);
		}
		static <T extends Def<T>, A1> MethodHandle partialDataConstructor(Class<T> type, Set<String> names, boolean fullCtor){
			return DefInstanceCompiler.dataConstructor(new DefInstanceCompiler.Key<>(type, Match.of(names)), fullCtor);
		}
		
		static <T extends Def<T>> Class<T> partialImplementation(Class<T> type, Set<String> includedFieldNames){
			return DefInstanceCompiler.getImplPartial(new DefInstanceCompiler.Key<>(type, Match.of(includedFieldNames)));
		}
	}
	
	abstract non-sealed class Managed<SELF extends Managed<SELF>> implements IOInstance<SELF>{
		static{ allowFullAccess(MethodHandles.lookup()); }
		
		protected static void registerAccess(Class<?> lookupProvider){
			registerAccess0(lookupProvider, true);
		}
		protected static void registerAccess(Class<?> lookupProvider, boolean selfCheck){
			registerAccess0(lookupProvider, selfCheck);
		}
		protected static void allowFullAccess(MethodHandles.Lookup lookup){
			Access.addLookup(lookup);
		}
		
		private Struct<SELF>  thisStruct;
		private VarPool<SELF> virtualFields;
		
		public Managed(){ }
		
		public Managed(Struct<SELF> thisStruct){
			if(DEBUG_VALIDATION) checkStruct(thisStruct);
			this.thisStruct = thisStruct;
			virtualFields = getThisStruct().allocVirtualVarPool(INSTANCE);
		}
		
		private void checkStruct(Struct<SELF> thisStruct){
			if(!thisStruct.getConcreteType().equals(getClass())){
				throw new IllegalArgumentException(thisStruct + " is not " + getClass());
			}
		}
		
		@SuppressWarnings("unchecked")
		private Struct<SELF> fetchStruct(boolean now){
			if(now){
				return Struct.of((Class<SELF>)getClass(), StagedInit.STATE_DONE);
			}
			return Struct.of((Class<SELF>)getClass());
		}
		
		private void init(){
			thisStruct = fetchStruct(true);
			virtualFields = thisStruct.allocVirtualVarPool(INSTANCE);
		}
		
		@Override
		public Struct<SELF> getThisStruct(){
			if(thisStruct == null) init();
			return thisStruct;
		}
		
		private VarPool<SELF> getVirtualPool(){
			if(thisStruct == null) init();
			return virtualFields;
		}
		
		@SuppressWarnings("unchecked")
		protected final SELF self(){ return (SELF)this; }
		
		@Override
		public String toString(){
			return getThisStruct().instanceToString(self(), false);
		}
		@Override
		public String toShortString(){
			return getThisStruct().instanceToString(self(), true);
		}
		
		@Override
		public boolean equals(Object o){
			if(o == null || o.getClass() != this.getClass()){
				return false;
			}
			
			//noinspection unchecked
			SELF dis = (SELF)this, that = (SELF)o;
			
			for(var field : getThisStruct().getRealFields()){
				if(!field.instancesEqual(null, dis, null, that)){
					return false;
				}
			}
			
			return true;
		}
		
		@Override
		public int hashCode(){
			int result = 1;
			for(var field : getThisStruct().getRealFields()){
				result = 31*result + field.instanceHashCode(null, self());
			}
			return result;
		}
		
		@Override
		public void allocateNulls(DataProvider provider, GenericContext genericContext) throws IOException{
			var ctx = genericContext;
			if(ctx == null && this instanceof IOInstance.Unmanaged<?> u) ctx = u.getGenerics();
			
			var s = thisStruct;
			if(s == null){
				s = fetchStruct(false);
				if(s.getInitializationState() == StagedInit.STATE_DONE){
					s = getThisStruct();
				}
			}
			
			for(var field : s.getRealFields()){
				if(!field.isNull(null, self())){
					if(field.getNullability() == IONullability.Mode.DEFAULT_IF_NULL){
						field.get(null, self());
					}
					continue;
				}
				if(field instanceof RefField<SELF, ?> ref){
					ref.allocate(self(), provider, ctx);
				}
			}
			
			for(var pair : s.getNullContainInstances()){
				var field = pair.field();
				if(!field.isNull(null, self())){
					continue;
				}
				var struct = pair.struct();
				var val    = struct.make();
				val.allocateNulls(provider, field.makeContext(ctx));
				//noinspection rawtypes,unchecked
				((IOField)field).set(null, self(), val);
			}
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public SELF clone(){
			var struct = getThisStruct();
			if(struct.needsBuilderObj()){
				return immutableDeepClone(struct);
			}
			
			SELF copy;
			try{
				copy = (SELF)super.clone();
			}catch(CloneNotSupportedException e){
				throw new RuntimeException(e);
			}
			deepClone(copy);
			return copy;
		}
		
		private SELF immutableDeepClone(Struct<SELF> struct){
			var builderTyp = struct.getBuilderObjType(false);
			
			var builder = builderTyp.make();
			builder.copyFrom(self());
			builder = builder.clone();//deep clone fields
			
			return builder.build();
		}
		
		@SuppressWarnings("unchecked")
		private void deepClone(SELF copy){
			var fields = getThisStruct().getCloneFields();
			if(fields.isEmpty()) return;
			
			for(IOField<SELF, ?> field : fields){
				var acc = field.getAccessor();
				var typ = acc.getType();
				if(typ.isArray()){
					var arrField = (IOField<SELF, Object[]>)field;
					
					var arr = arrField.get(null, (SELF)this);
					if(arr == null || arr.length == 0) continue;
					
					arr = arr.clone();
					
					if(isInstance(typ.componentType())){
						var iArr = (IOInstance<?>[])arr;
						for(int i = 0; i<iArr.length; i++){
							var el = iArr[i];
							iArr[i] = el.clone();
						}
					}
					
					arrField.set(null, copy, arr);
					continue;
				}
				
				if(!isInstance(typ)) continue;
				if(isUnmanaged(typ)) continue;
				var instField = (IOField<SELF, IOInstance<?>>)field;
				
				var val = instField.get(null, (SELF)this);
				if(val == null) continue;
				
				val = val.clone();
				
				instField.set(null, copy, val);
			}
		}
	}
	
	abstract class Unmanaged<SELF extends Unmanaged<SELF>> extends IOInstance.Managed<SELF> implements DataProvider.Holder{
		
		public interface DynamicFields<SELF extends Unmanaged<SELF>>{
			@NotNull
			Iterable<IOField<SELF, ?>> listDynamicUnmanagedFields();
		}
		
		protected static void allowFullAccess(MethodHandles.Lookup lookup){
			Access.addLookup(lookup);
		}
		
		private final DataProvider   provider;
		private       Chunk          identity;
		private final IOType         typeDef;
		private       GenericContext genericCtx;
		
		private StructPipe<SELF> pipe;
		
		protected final boolean readOnly;
		
		protected Unmanaged(Struct<SELF> thisStruct, DataProvider provider, Chunk identity, IOType typeDef, TypeCheck check){
			this(thisStruct, provider, identity, typeDef);
			check.ensureValid(typeDef, provider.getTypeDb());
		}
		
		protected Unmanaged(DataProvider provider, Chunk identity, IOType typeDef, TypeCheck check){
			this(provider, identity, typeDef);
			check.ensureValid(typeDef, provider.getTypeDb());
		}
		
		public Unmanaged(Struct<SELF> thisStruct, DataProvider provider, Chunk identity, IOType typeDef){
			super(thisStruct);
			this.provider = Objects.requireNonNull(provider);
			setIdentity(identity);
			this.typeDef = typeDef;
			readOnly = getDataProvider().isReadOnly();
		}
		
		public Unmanaged(DataProvider provider, Chunk identity, IOType typeDef){
			this.provider = Objects.requireNonNull(provider);
			setIdentity(identity);
			this.typeDef = typeDef;
			readOnly = getDataProvider().isReadOnly();
		}
		
		private void setIdentity(Reference reference){
			try{
				setIdentity(reference.asJustChunk(provider));
			}catch(IOException e){
				throw new UncheckedIOException(e);
			}
		}
		private void setIdentity(Chunk reference){
			if(DEBUG_VALIDATION){
				try{
					reference.requireReal();
				}catch(IOException e){
					throw new UncheckedIOException(e);
				}
			}
			identity = reference;
		}
		
		private Chunk getIdentity(){
			return identity;
		}
		
		@SuppressWarnings("unchecked")
		@NotNull
		public final Iterable<IOField<SELF, ?>> listUnmanagedFields(){
			var s  = getThisStruct();
			var fs = s.getUnmanagedStaticFields();
			if(!s.hasDynamicFields()){
				return fs;
			}
			var dynamic = ((DynamicFields<SELF>)this).listDynamicUnmanagedFields();
			if(fs.isEmpty()) return dynamic;
			
			return Iters.concat(fs, dynamic);
		}
		
		public CommandSet.CmdReader getUnmanagedReferenceWalkCommands(){
			if(getThisStruct().hasDynamicFields()){
				throw new NotImplementedException(getThisStruct() + " has dynamic fields! Please implement walk commands!");
			}else{
				throw new UnsupportedOperationException();
			}
		}
		
		public final IOType getTypeDef(){
			return typeDef;
		}
		
		
		public final GenericContext getGenerics(){
			if(genericCtx == null){
				genericCtx = getThisStruct().describeGenerics(typeDef, getDataProvider().getTypeDb());
			}
			return genericCtx;
		}
		
		@Override
		public final DataProvider getDataProvider(){
			return provider;
		}
		
		protected final boolean isSelfDataEmpty(){
			var id = getIdentity();
			if(id.getSize()>0){
				return false;
			}
			try{
				var next = id.next();
				if(next != null){
					for(Chunk chunk : next.walkNext()){
						if(chunk.getSize()>0){
							return false;
						}
					}
				}
			}catch(IOException e){
				throw new UncheckedIOException(e);
			}
			return true;
		}
		
		public final void notifyReferenceMovement(Chunk newRef){
			Objects.requireNonNull(newRef);
			
			if(DEBUG_VALIDATION) ensureDataIntegrity(newRef);
			
			setIdentity(newRef);
		}
		
		private void ensureDataIntegrity(Chunk newRef){
			byte[] oldData, newData;
			try(var oldIo = selfIO();
			    var newIo = newRef.io()
			){
				oldData = oldIo.readRemaining();
				newData = newIo.readRemaining();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			if(!Arrays.equals(oldData, newData)){
				throw new IllegalStateException(
					"Data changed when moving reference! This is invalid behaviour\n" +
					Arrays.toString(oldData) + "\n" +
					Arrays.toString(newData)
				);
			}
		}
		
		@Override
		public final Struct.Unmanaged<SELF> getThisStruct(){
			return (Struct.Unmanaged<SELF>)super.getThisStruct();
		}
		
		private boolean freed;
		
		public boolean isFreed(){
			return freed;
		}
		protected void notifyFreed(){
			freed = true;
		}
		
		public void free() throws IOException{
			MemoryOperations.freeSelfAndReferenced(self());
			notifyFreed();
		}
		
		protected final void selfIO(UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			try(var io = selfIO()){
				session.accept(io);
			}
		}
		
		protected final ChunkChainIO selfIO() throws IOException{
			return getIdentity().io();
		}
		
		protected StructPipe<SELF> newPipe(){
			return StandardStructPipe.of(getThisStruct());
		}
		
		public final StructPipe<SELF> getPipe(){
			if(pipe == null) pipe = newPipe();
			return pipe;
		}
		
		@SuppressWarnings("unchecked")
		public <VT extends IOInstance<VT>> StructPipe<VT> getFieldPipe(IOField<SELF, VT> unmanagedField, VT fieldValue){
			Struct<VT> struct;
			if(unmanagedField.typeFlag(IOField.DYNAMIC_FLAG)){
				struct = fieldValue.getThisStruct();
			}else{
				struct = Struct.of((Class<VT>)unmanagedField.getType());
			}
			
			return StructPipe.of(pipe.getClass(), struct);
		}
		
		protected final void writeManagedFields() throws IOException{
			if(readOnly){
				throw new UnsupportedOperationException();
			}
			var io = selfIO();
			try(io){
				getPipe().write(provider, io, self());
				var struct = getThisStruct();
				if(!struct.hasDynamicFields() && struct.getUnmanagedStaticFields().isEmpty()){
					io.trim();
				}
			}
		}
		protected final void readManagedFields() throws IOException{
			try(var io = selfIO()){
				getPipe().read(provider, io, self(), getGenerics());
			}
		}
		
		protected final void readManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io = selfIO()){
				var pip = getPipe();
				pip.readDeps(pip.makeIOPool(), provider, io, pip.getFieldDependency().getDeps(field), self(), getGenerics());
			}
		}
		
		protected final void writeManagedField(IOField<SELF, ?> field) throws IOException{
			try(var io = selfIO()){
				getPipe().writeSingleField(provider, io, field, self());
			}
		}
		
		protected final long calcInstanceSize(WordSpace wordSpace){
			var pip = getPipe();
			var siz = pip.getSizeDescriptor();
			var f   = siz.getFixed(wordSpace);
			if(f.isPresent()) return f.getAsLong();
			return siz.calcUnknown(pip.makeIOPool(), getDataProvider(), self(), wordSpace);
		}
		
		public final ChunkPointer getPointer(){
			return getIdentity().getPtr();
		}
		
		protected final void allocateNulls() throws IOException{
			allocateNulls(getDataProvider(), getGenerics());
		}
		
		@Override
		public SELF clone() throws UnsupportedOperationException{
			throw new UnsupportedOperationException("Unmanaged objects can not be cloned");
		}
		
		@Override
		public boolean equals(Object o){
			return o == this ||
			       o instanceof IOInstance.Unmanaged<?> that &&
			       this.getIdentity().equals(that.getIdentity());
		}
		@Override
		public int hashCode(){
			return getIdentity().hashCode();
		}
	}
	
	Struct<SELF> getThisStruct();
	
	void allocateNulls(DataProvider provider, GenericContext genericContext) throws IOException;
	
	@Override
	default String toShortString(){
		return getThisStruct().instanceToString(self(), true);
	}
	
	default String toString(boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return toString(new StringifySettings(doShort, start, end, fieldValueSeparator, fieldSeparator));
	}
	default String toString(StringifySettings settings){
		return getThisStruct().instanceToString(self(), settings);
	}
	
	SELF clone();
	
	@SuppressWarnings("unchecked")
	private SELF self(){ return (SELF)this; }
	
	
	static boolean isInstance(SealedUtil.SealedUniverse<?> universe){
		return Iters.from(universe.universe()).allMatch(IOInstance::isInstance);
	}
	static boolean isInstance(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.class);
	}
	static boolean isInstanceOrSealed(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.class) ||
		       SealedUtil.getSealedUniverse(type, false).filter(IOInstance::isInstance).isPresent();
	}
	
	static boolean isUnmanaged(Class<?> type){
		return UtilL.instanceOf(type, IOInstance.Unmanaged.class);
	}
	static boolean isManaged(Class<?> type){
		var isInstance  = isInstance(type);
		var isUnmanaged = UtilL.instanceOf(type, IOInstance.Unmanaged.class);
		return isInstance && !isUnmanaged;
	}
	
	
	static Void allowFullAccessI(MethodHandles.Lookup lookup){
		Access.addLookup(lookup);
		return null;
	}
	static Void registerAccessI(Class<?> lookupProvider){
		registerAccess0(lookupProvider, true);
		return null;
	}
	static Void registerAccessI(Class<?> lookupProvider, boolean selfCheck){
		registerAccess0(lookupProvider, selfCheck);
		return null;
	}
	
	private static void registerAccess0(Class<?> lookupProvider, boolean selfCheck){
		AccessProvider provider;
		if(selfCheck){
			var callee = Utils.getCallee(2);
			provider = new AccessProvider.WeaklyProvidedLookup(lookupProvider, callee);
		}else{
			provider = new AccessProvider.WeaklyProvidedLookup(lookupProvider);
		}
		Access.registerProvider(provider);
	}
}
