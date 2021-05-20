package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.exceptions.MalformedObjectException;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.VariableNode.FixedSize;
import com.lapissea.cfs.io.struct.engine.StructReflectionImpl.NodeMaker.FunDef;
import com.lapissea.cfs.io.struct.engine.StructReflectionImpl.NodeMaker.FunDef.Arg;
import com.lapissea.cfs.io.struct.engine.impl.BitBlockNode;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.*;
import static java.util.stream.Collectors.*;

@SuppressWarnings("unchecked")
public class IOStruct implements Type{
	
	@SuppressWarnings("unchecked")
	public static final List<Class<Annotation>> ANNOTATIONS=Arrays.stream(IOStruct.class.getClasses()).filter(Class::isAnnotation).map(c->(Class<Annotation>)c).collect(toUnmodifiableList());
	public static final List<Class<Annotation>> VALUE_TYPES=ANNOTATIONS.stream().filter(n->n.getSimpleName().endsWith("Value")).collect(toUnmodifiableList());
	public static final List<Class<Annotation>> TAG_TYPES  =ANNOTATIONS.stream().filter(n->!VALUE_TYPES.contains(n)).collect(toUnmodifiableList());
	
	@Target(METHOD)
	@Retention(RUNTIME)
	public @interface Construct{
		
		interface Constructor<T>{
			@Nullable
			static <T> Constructor<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(valueType,
				                  new Arg(Chunk.class, "source"))
					       .getOverride(info, IOStruct.Construct.Constructor.class);
			}
			
			T construct(IOInstance target, Chunk source) throws IOException;
		}
		
		String target() default "";
	}
	
	@Target(METHOD)
	@Retention(RUNTIME)
	public @interface Read{
		
		interface Reader<T>{
			@Nullable
			static <T> Reader<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(valueType,
				                  new Arg(Cluster.class, "cluster"),
				                  new Arg(ContentReader.class, "source"),
				                  new Arg(valueType, "oldVal"))
					       .getOverride(info, IOStruct.Read.Reader.class);
			}
			
			T read(IOInstance target, Cluster cluster, ContentReader source, T oldVal) throws IOException;
		}
		
		String target() default "";
	}
	
	@Target(METHOD)
	@Retention(RUNTIME)
	public @interface Write{
		
		interface Writer<T>{
			@Nullable
			static <T> Writer<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(Void.TYPE,
				                  new Arg(Cluster.class, "cluster"),
				                  new Arg(ContentWriter.class, "dest"),
				                  new Arg(valueType, "source"))
					       .getOverride(info, Writer.class);
			}
			
			void write(IOInstance target, Cluster cluster, ContentWriter dest, T source) throws IOException;
		}
		
		String target() default "";
	}
	
	@Target(METHOD)
	@Retention(RUNTIME)
	public @interface Get{
		
		interface Getter<T>{
			@Nullable
			static <T> Getter<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(valueType)
					       .getOverride(info, Getter.class);
			}
			
			T getValue(IOInstance target);
		}
		
		interface GetterB{
			@Nullable
			static GetterB get(ValueRelations.ValueInfo info){
				return new FunDef(boolean.class)
					       .getOverride(info, GetterB.class);
			}
			
			boolean getValue(IOInstance target);
		}
		
		interface GetterL{
			@Nullable
			static GetterL get(ValueRelations.ValueInfo info){
				return new FunDef(long.class)
					       .getOverride(info, GetterL.class);
			}
			
			long getValue(IOInstance target);
		}
		
		interface GetterI{
			@Nullable
			static GetterI get(ValueRelations.ValueInfo info){
				return new FunDef(int.class)
					       .getOverride(info, GetterI.class);
			}
			
			int getValue(IOInstance target);
		}
		
		interface GetterF{
			@Nullable
			static GetterF get(ValueRelations.ValueInfo info){
				return new FunDef(int.class)
					       .getOverride(info, GetterF.class);
			}
			
			float getValue(IOInstance target);
		}
		
		String target() default "";
	}
	
	@Retention(RUNTIME)
	public @interface Set{
		
		interface Setter<T>{
			@Nullable
			static <T> Setter<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(Void.TYPE,
				                  new Arg(valueType, "newValue"))
					       .getOverride(info, Setter.class);
			}
			
			void setValue(IOInstance target, T newValue);
		}
		
		interface SetterB{
			@Nullable
			static SetterB get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(boolean.class, "newValue"))
					       .getOverride(info, SetterB.class);
			}
			
			void setValue(IOInstance target, boolean newValue);
		}
		
		interface SetterL{
			@Nullable
			static SetterL get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(long.class, "newValue"))
					       .getOverride(info, SetterL.class);
			}
			
			void setValue(IOInstance target, long newValue);
		}
		
		interface SetterI{
			@Nullable
			static SetterI get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(int.class, "newValue"))
					       .getOverride(info, SetterI.class);
			}
			
			void setValue(IOInstance target, int newValue);
		}
		
		interface SetterF{
			@Nullable
			static SetterF get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(int.class, "newValue"))
					       .getOverride(info, SetterF.class);
			}
			
			void setValue(IOInstance target, float newValue);
		}
		
		String target() default "";
	}
	
	@Retention(RUNTIME)
	public @interface Size{
		
		interface Sizer<T>{
			@Nullable
			static <T> IOStruct.Size.Sizer<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(long.class,
				                  new Arg(valueType, "value"))
					       .getOverride(info, IOStruct.Size.Sizer.class);
			}
			
			long mapSize(IOInstance target, T value);
		}
		
		long fixedSize() default -1;
		
		String target() default "";
	}
	
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface Value{
		int index();
		
		Class<? extends ReaderWriter> rw() default ReaderWriter.class;
		
		String[] rwArgs() default {};
	}
	
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface EnumValue{
		int index();
		
		boolean nullable() default false;
		
		byte customBitSize() default -1;
	}
	
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface PrimitiveValue{
		int index();
		
		NumberSize defaultSize() default NumberSize.VOID;
		
		String sizeRef() default "";
	}
	
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface PointerValue{
		int index();
		
		Class<? extends ReaderWriter<ObjectPointer<?>>> rw() default ObjectPointer.FixedNoOffsetIO.class;
		
		String[] rwArgs() default {};
		
		Class<? extends IOInstance> type() default IOInstance.class;
	}
	
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface PrimitiveArrayValue{
		int index();
		
		int fixedElements() default -1;
		
		NumberSize defaultSize() default NumberSize.VOID;
		
		String sizeRef() default "";
	}
	
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface ArrayValue{
		int index();
		
		int fixedElements() default -1;
		
		Class<? extends ReaderWriter> rw() default ReaderWriter.class;
		
		String[] rwArgs() default {};
	}
	
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface GlobalSetValue{
		int index();
		
		Class<? extends IOInstance> type() default IOInstance.class;
	}
	
	public static class ClusterDict implements ReaderWriter<IOStruct>{
		
		private static final NumberSize NUMBER_SIZE=NumberSize.SMALL_INT;
		
		@Override
		public IOStruct read(Object targetObj, Cluster cluster, ContentReader source, IOStruct oldValue) throws IOException{
			int index=(int)NUMBER_SIZE.read(source);
			return fromIndex(cluster, index);
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, IOStruct source) throws IOException{
			NUMBER_SIZE.write(target, makeIndex(cluster, source));
		}
		
		
		private IOStruct fromIndex(Cluster cluster, int index) throws IOException{
			IOList<String> names=cluster.getGlobalStrings();
			String         name =names.getElement(index);
			try{
				return IOStruct.ofUnknown(Class.forName(name));
			}catch(ClassNotFoundException e){
				throw new MalformedObjectException(name+" not found", e);
			}
		}
		
		private int makeIndex(Cluster cluster, IOStruct struct) throws IOException{
			IOList<String> names=cluster.getGlobalStrings();
			String         name =struct.instanceClass.getName();
			
			int index=names.indexOf(name);
			if(index!=-1) return index;
			
			names.addElement(name);
			return names.indexOf(name);
		}
		
		@Override
		public long mapSize(Object targetObj, IOStruct source){
			return NUMBER_SIZE.bytes;
		}
		
		@Override
		public OptionalInt getFixedSize(){
			return NUMBER_SIZE.optionalBytes;
		}
		
		@Override
		public OptionalInt getMaxSize(){
			return getFixedSize();
		}
	}
	
	private static final Map<Class<?>, IOStruct> CACHE=new HashMap<>();
	
	public static IOStruct thisClass(){
		return ofUnknown(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                            .walk(s->s.skip(1).findFirst().orElseThrow().getDeclaringClass()));
	}
	
	public static IOStruct ofUnknown(@NotNull Class<?> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(!UtilL.instanceOf(instanceClass, IOInstance.class)){
			throw new IllegalArgumentException(instanceClass.getName()+" is not an IOInstance");
		}
		
		return of((Class<? extends IOInstance>)instanceClass);
	}
	
	public static IOStruct of(@NotNull Class<? extends IOInstance> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(instanceClass==IOInstance.class) throw new IllegalArgumentException("Cannot make IOStruct from raw IOInstance");
		
		var result=CACHE.get(instanceClass);
		if(result==null){
			result=new IOStruct(instanceClass);
			CACHE.put(instanceClass, result);
			try{
				result.compile();
			}catch(Throwable e){
				CACHE.remove(instanceClass);
				throw new RuntimeException("Failed to compile struct "+instanceClass.getName(), e);
			}
		}
		return result;
	}
	
	public final Class<? extends IOInstance> instanceClass;
	
	private List<VariableNode<Object>> variables;
	
	VariableNode<?>[] variableIter;
	
	private OptionalLong knownSize  =OptionalLong.empty();
	private long         minimumSize;
	private OptionalLong maximumSize=OptionalLong.empty();
	
	private UnsafeFunction<Chunk, ? extends IOInstance, IOException> constructor;
	
	private boolean simpleIndex;
	
	private IOStruct(Class<? extends IOInstance> instanceClass){
		this.instanceClass=instanceClass;
	}
	
	private void compile(){
		constructor=findConstructor();
		
		variables=StructImpl.generateVariablesDefault(this);
		
		simpleIndex=IntStream.range(0, getVariables().size()).allMatch(i->getVariables().get(i).info.index()==i);
		
		for(int i=0;i<getVariables().size();i++){
			getVariables().get(i).postProcess(i, getVariables());
		}
		
		variableIter=computeIter();
		
		Offset offset=Offset.ZERO;
		
		for(var var : variableIter){
			var.applyKnownOffset(offset);
			if(var instanceof VariableNode.FixedSize fs){
				offset=offset.add(Offset.fromBytes(fs.getSize()));
			}else break;
		}
		
		knownSize=calculateKnownSize();
		minimumSize=calculateMinimumSize();
		maximumSize=calculateMaximumSize();


//		logStruct();
	}
	
	private UnsafeFunction<Chunk, ? extends IOInstance, IOException> findConstructor(){
		
		for(Method method : instanceClass.getMethods()){
			if("constructThis".equals(method.getName())&&method.getDeclaredAnnotation(Construct.class)!=null){
				return Utils.makeLambda(method, Construct.Constructor.class);
			}
		}
		try{
			BiFunction<Constructor<IOInstance>, Object[], IOInstance> make=(c, args)->{
				try{
					return (IOInstance)c.newInstance(args);
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			};
			
			return Utils.tryMapConstructor(
				(Class<IOInstance>)instanceClass,
				List.of(
					Stream::empty,
					()->Stream.of(Chunk.class)),
				(BiFunction<Integer, Constructor<IOInstance>, UnsafeFunction<Chunk, IOInstance, IOException>>)(i, c)->{
					c.setAccessible(true);
					return switch(i){
						case 0 -> (chunk)->make.apply(c, ZeroArrays.ZERO_OBJECT);
						case 1 -> (chunk)->make.apply(c, new Object[]{chunk});
						default -> throw new RuntimeException();
					};
				}
			                              );
		}catch(ReflectiveOperationException e){
			return null;
		}
	}
	
	private OptionalLong calculateMaximumSize(){
		long result=0;
		for(VariableNode<?> variableNode : variableIter){
			var max=variableNode.getMaximumSize();
			if(max.isEmpty()) return OptionalLong.empty();
			result+=max.getAsLong();
		}
		
		return OptionalLong.of(result);
	}
	
	private long calculateMinimumSize(){
		return Arrays.stream(variableIter)
		             .filter(var->var instanceof FixedSize)
		             .mapToLong(var->((FixedSize)var).getSize())
		             .sum();
	}
	
	private OptionalLong calculateKnownSize(){
		if(getVariables().isEmpty()) return OptionalLong.of(0);
		
		var last=getVariables().get(getVariables().size()-1);
		if(last instanceof FixedSize fix){
			var off=last.getKnownOffset();
			if(off!=null){
				return OptionalLong.of(off.getOffset()+fix.getSize());
			}
		}
		
		return OptionalLong.empty();
	}
	
	private VariableNode<?>[] computeIter(){
		List<VariableNode<?>> indexBuilder=new ArrayList<>(getVariables().size());
		
		for(int i=0;i<getVariables().size();){
			var v=getVariables().get(i);
			
			if(v instanceof VariableNode.Flag<?> ff){
				try{
					var block=new BitBlockNode(this, i);
					if(block.blockInfo().count()>1){
						indexBuilder.add(block);
						i=block.blockInfo().end();
						continue;
					}
				}finally{
					ff.clearBlockInfo();
				}
			}
			
			indexBuilder.add(v);
			i++;
			
		}
		
		
		return indexBuilder.toArray(VariableNode[]::new);
	}
	
	public long requireKnownSize(){
		return getKnownSize().orElseThrow(()->new IllegalStateException(this+" must have a known size!"));
	}
	
	public OptionalLong getKnownSize(){
		return knownSize;
	}
	
	public long getMinimumSize(){
		return minimumSize;
	}
	
	public long requireMaximumSize(){
		return getMaximumSize().orElseThrow(()->new IllegalStateException(this+" must have a maximum size!"));
	}
	
	public OptionalLong getMaximumSize(){
		return maximumSize;
	}
	
	
	@Override
	public String toString(){
		StringBuilder result=new StringBuilder("IOStruct{");
		result.append(instanceClass.getName()).append(", ").append(getVariables().size()).append(" values");
		
		if(getKnownSize().isPresent()){
			result.append(", fixed size: ").append(requireKnownSize()).append(" bytes");
		}
		
		return result.append("}").toString();
	}
	
	public String toShortString(){
		StringBuilder result=new StringBuilder("IoS{");
		result.append(instanceClass.getSimpleName());
		
		if(getKnownSize().isPresent()){
			result.append(", ").append(requireKnownSize()).append('b');
		}
		
		return result.append("}").toString();
	}
	
	
	public void logStruct(){
		LogUtil.println(this);
		LogUtil.println(TextUtil.toTable("variables", getVariables()));
		var iter=List.of(variableIter);
		if(!getVariables().equals(iter)){
			LogUtil.println(TextUtil.toTable("Iteration variables", iter));
		}
	}
	
	public <V extends VariableNode<?>> V findVar(Predicate<VariableNode<?>> finder){
		return findVar(finder, ()->"Could not find var in "+this);
	}
	
	@NotNull
	public <V extends VariableNode<?>> V findVar(Predicate<VariableNode<?>> finder, Supplier<String> errorMsg){
		return (V)getVariables().stream().filter(finder).findAny().orElseThrow(()->new RuntimeException(errorMsg.get()));
	}
	
	@NotNull
	public <V extends VariableNode<?>> V getVar(int varIndex){
		if(simpleIndex) return (V)getVariables().get(varIndex);
		return findVar(v->v.info.index()==varIndex, ()->"Var with index "+varIndex+" does not exist in "+this);
	}
	
	public <V extends VariableNode<?>> V getVar(String varName){
		return findVar(v->v.info.name().equals(varName), ()->varName+" does not exist in "+this);
	}
	
	public boolean canInstate(){
		return constructor!=null;
	}
	
	public <T extends IOInstance> T newInstance(Chunk chunk) throws IOException{
		if(!canInstate()) throw new UnsupportedOperationException(this.instanceClass.getName()+" does not support auto construction");
		return (T)constructor.apply(chunk);
	}
	public List<VariableNode<Object>> getVariables(){
		return variables;
	}
	
	@Override
	public String getTypeName(){
		return instanceClass.getTypeName();
	}
}
