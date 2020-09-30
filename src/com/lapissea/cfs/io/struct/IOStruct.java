package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.VariableNode.FixedSize;
import com.lapissea.cfs.io.struct.engine.StructReflectionImpl.NodeMaker.FunDef;
import com.lapissea.cfs.io.struct.engine.StructReflectionImpl.NodeMaker.FunDef.Arg;
import com.lapissea.cfs.io.struct.engine.impl.BitBlockNode;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.*;

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
public class IOStruct{
	
	@SuppressWarnings("unchecked")
	public static final List<Class<Annotation>> ANNOTATIONS=Arrays.stream(IOStruct.class.getClasses()).filter(Class::isAnnotation).map(c->(Class<Annotation>)c).collect(toUnmodifiableList());
	public static final List<Class<Annotation>> VALUE_TYPES=ANNOTATIONS.stream().filter(n->n.getSimpleName().endsWith("Value")).collect(toUnmodifiableList());
	public static final List<Class<Annotation>> TAG_TYPES  =ANNOTATIONS.stream().filter(n->!VALUE_TYPES.contains(n)).collect(toUnmodifiableList());
	
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
	
	@Retention(RUNTIME)
	public @interface Read{
		
		interface Reader<T>{
			@Nullable
			static <T> Reader<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(valueType,
				                  new Arg(ContentReader.class, "source"),
				                  new Arg(valueType, "oldVal"))
					       .getOverride(info, IOStruct.Read.Reader.class);
			}
			
			T read(IOInstance target, ContentReader source, T oldVal) throws IOException;
		}
		
		String target() default "";
	}
	
	@Retention(RUNTIME)
	public @interface Write{
		
		interface Writer<T>{
			@Nullable
			static <T> Writer<T> get(ValueRelations.ValueInfo info, Type valueType){
				return new FunDef(Void.TYPE,
				                  new Arg(ContentWriter.class, "dest"),
				                  new Arg(valueType, "source"))
					       .getOverride(info, Writer.class);
			}
			void write(IOInstance target, ContentWriter dest, T source) throws IOException;
		}
		
		String target() default "";
	}
	
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
			static <T> GetterB get(ValueRelations.ValueInfo info){
				return new FunDef(boolean.class)
					       .getOverride(info, GetterB.class);
			}
			boolean getValue(IOInstance target);
		}
		
		interface GetterL{
			@Nullable
			static <T> GetterL get(ValueRelations.ValueInfo info){
				return new FunDef(long.class)
					       .getOverride(info, GetterL.class);
			}
			long getValue(IOInstance target);
		}
		
		interface GetterI{
			@Nullable
			static <T> GetterI get(ValueRelations.ValueInfo info){
				return new FunDef(int.class)
					       .getOverride(info, GetterI.class);
			}
			int getValue(IOInstance target);
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
			static <T> SetterB get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(boolean.class, "newValue"))
					       .getOverride(info, SetterB.class);
			}
			void setValue(IOInstance target, boolean newValue);
		}
		
		interface SetterL{
			@Nullable
			static <T> SetterL get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(long.class, "newValue"))
					       .getOverride(info, SetterL.class);
			}
			void setValue(IOInstance target, long newValue);
		}
		
		interface SetterI{
			@Nullable
			static <T> SetterI get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(int.class, "newValue"))
					       .getOverride(info, SetterI.class);
			}
			void setValue(IOInstance target, int newValue);
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
	
	private static final Map<Class<?>, IOStruct> CACHE=new HashMap<>();
	
	public static IOStruct thisClass(){
		Class<?> type=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                         .walk(s->s.skip(1)
		                                   .findFirst()
		                                   .orElseThrow()
		                                   .getDeclaringClass()
		                         );
		return get((Class<? extends IOInstance>)type);
	}
	
	public static IOStruct get(Class<? extends IOInstance> instanceClass){
		var result=CACHE.get(instanceClass);
		if(result==null){
			result=new IOStruct(instanceClass);
			CACHE.put(instanceClass, result);
		}
		return result;
	}
	
	public final Class<? extends IOInstance> instanceClass;
	
	public final List<VariableNode<Object>> variables;
	
	final VariableNode<?>[] variableIter;
	
	private final OptionalLong knownSize;
	private final long         minimumSize;
	private final OptionalLong maximumSize;
	
	private final Construct.Constructor<?> constructor;
	
	private final boolean simpleIndex;
	
	private IOStruct(Class<? extends IOInstance> instanceClass){
		this.instanceClass=instanceClass;
		
		variables=StructImpl.generateVariablesDefault((Class<IOInstance>)instanceClass);
		
		simpleIndex=IntStream.range(0, variables.size()).allMatch(i->variables.get(i).index==i);
		
		for(int i=0;i<variables.size();i++){
			variables.get(i).postProcess(i, variables);
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
		
		constructor=findConstructor();

//		logStruct();
	}
	
	private Construct.Constructor<? extends IOInstance> findConstructor(){
		
		for(Method method : instanceClass.getMethods()){
			if(method.getName().equals("constructThis")&&method.getDeclaredAnnotation(Construct.class)!=null){
				return Utils.makeLambda(method, Construct.Constructor.class);
			}
		}
		try{
			BiFunction<Constructor<IOInstance>, Object[], IOInstance> make=(c, args)->{
				try{
					return (IOInstance)c.newInstance();
				}catch(ReflectiveOperationException e){
					throw new RuntimeException(e);
				}
			};
			
			return Utils.tryMapConstructor((Class<IOInstance>)instanceClass, List.of(
				Stream::empty,
				()->Stream.of(Chunk.class)
			), (BiFunction<Integer, Constructor<IOInstance>, Construct.Constructor<IOInstance>>)(i, c)->switch(i){
				case 0 -> (t, chunk)->make.apply(c, ZeroArrays.ZERO_OBJECT);
				case 1 -> (t, chunk)->make.apply(c, new Object[]{chunk});
				default -> throw new RuntimeException();
			});
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
		if(variables.isEmpty()) return OptionalLong.of(0);
		
		var last=variables.get(variables.size()-1);
		if(last instanceof FixedSize fix){
			var off=last.getKnownOffset();
			if(off!=null){
				return OptionalLong.of(off.getOffset()+fix.getSize());
			}
		}
		
		return OptionalLong.empty();
	}
	
	private VariableNode<?>[] computeIter(){
		List<VariableNode<?>> indexBuilder=new ArrayList<>(variables.size());
		
		for(int i=0;i<variables.size();){
			var v=variables.get(i);
			
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
		result.append(instanceClass.getName()).append(", ").append(variables.size()).append(" values");
		
		if(getKnownSize().isPresent()){
			result.append(", fixed size: ").append(requireKnownSize()).append(" bytes");
		}
		
		return result.append("}").toString();
	}
	
	public void logStruct(){
		LogUtil.println(this);
		LogUtil.println(TextUtil.toTable("variables", variables));
		var iter=List.of(variableIter);
		if(!variables.equals(iter)){
			LogUtil.println(TextUtil.toTable("Iteration variables", iter));
		}
	}
	
	public <V extends VariableNode<?>> V findVar(Predicate<VariableNode<?>> finder){
		return findVar(finder, ()->"Could not find var in "+this);
	}
	
	@NotNull
	public <V extends VariableNode<?>> V findVar(Predicate<VariableNode<?>> finder, Supplier<String> errorMsg){
		return (V)variables.stream().filter(finder).findAny().orElseThrow(()->new RuntimeException(errorMsg.get()));
	}
	
	@NotNull
	public <V extends VariableNode<?>> V getVar(int varIndex){
		if(simpleIndex) return (V)variables.get(varIndex);
		return findVar(v->v.index==varIndex, ()->"Var with index "+varIndex+" does not exist in "+this);
	}
	
	public <V extends VariableNode<?>> V getVar(String varName){
		return findVar(v->v.name.equals(varName), ()->varName+" does not exist in "+this);
	}
	
	public boolean canInstate(){
		return constructor!=null;
	}
	public <T extends IOInstance> T newInstance(IOInstance target, Chunk chunk) throws IOException{
		if(!canInstate()) throw new UnsupportedOperationException(this.instanceClass.getName()+" does not support auto construction");
		return (T)constructor.construct(target, chunk);
	}
}
