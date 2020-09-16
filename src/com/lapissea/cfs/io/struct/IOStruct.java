package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.exceptions.MalformedObjectException;
import com.lapissea.cfs.exceptions.OutOfSyncDataException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ReaderWriter;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.content.SimpleContentWriter;
import com.lapissea.cfs.io.struct.VariableNode.FixedSize;
import com.lapissea.cfs.io.struct.engine.StructReflectionImpl.NodeMaker.FunDef;
import com.lapissea.cfs.io.struct.engine.StructReflectionImpl.NodeMaker.FunDef.Arg;
import com.lapissea.cfs.io.struct.engine.impl.BitBlockNode;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.cfs.Config.*;
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
	public @interface Read{
		
		interface Reader<T>{
			@Nullable
			static <T> Reader<T> get(ValueRelations.ValueInfo info, Class<T> valueType){
				return new FunDef(valueType,
				                  new Arg(ContentReader.class, "source"),
				                  new Arg(valueType, "oldVal"))
					       .getOverride(info, IOStruct.Read.Reader.class);
			}
			
			T read(IOStruct.Instance target, ContentReader source, T oldVal) throws IOException;
		}
		
		String target() default "";
	}
	
	@Retention(RUNTIME)
	public @interface Write{
		
		interface Writer<T>{
			@Nullable
			static <T> Writer<T> get(ValueRelations.ValueInfo info, Class<T> valueType){
				return new FunDef(Void.TYPE,
				                  new Arg(ContentWriter.class, "dest"),
				                  new Arg(valueType, "source"))
					       .getOverride(info, Writer.class);
			}
			void write(IOStruct.Instance target, ContentWriter dest, T source) throws IOException;
		}
		
		String target() default "";
	}
	
	@Retention(RUNTIME)
	public @interface Get{
		
		interface Getter<T>{
			@Nullable
			static <T> Getter<T> get(ValueRelations.ValueInfo info, Class<T> valueType){
				return new FunDef(valueType)
					       .getOverride(info, Getter.class);
			}
			T getValue(IOStruct.Instance target);
		}
		
		interface GetterB{
			@Nullable
			static <T> GetterB get(ValueRelations.ValueInfo info){
				return new FunDef(boolean.class)
					       .getOverride(info, GetterB.class);
			}
			boolean getValue(IOStruct.Instance target);
		}
		
		interface GetterL{
			@Nullable
			static <T> GetterL get(ValueRelations.ValueInfo info){
				return new FunDef(long.class)
					       .getOverride(info, GetterL.class);
			}
			long getValue(IOStruct.Instance target);
		}
		
		interface GetterI{
			@Nullable
			static <T> GetterI get(ValueRelations.ValueInfo info){
				return new FunDef(int.class)
					       .getOverride(info, GetterI.class);
			}
			int getValue(IOStruct.Instance target);
		}
		
		String target() default "";
	}
	
	@Retention(RUNTIME)
	public @interface Set{
		
		interface Setter<T>{
			@Nullable
			static <T> Setter<T> get(ValueRelations.ValueInfo info, Class<T> valueType){
				return new FunDef(Void.TYPE,
				                  new Arg(valueType, "newValue"))
					       .getOverride(info, Setter.class);
			}
			void setValue(IOStruct.Instance target, T newValue);
		}
		
		interface SetterB{
			@Nullable
			static <T> SetterB get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(boolean.class, "newValue"))
					       .getOverride(info, SetterB.class);
			}
			void setValue(IOStruct.Instance target, boolean newValue);
		}
		
		interface SetterL{
			@Nullable
			static <T> SetterL get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(long.class, "newValue"))
					       .getOverride(info, SetterL.class);
			}
			void setValue(IOStruct.Instance target, long newValue);
		}
		
		interface SetterI{
			@Nullable
			static <T> SetterI get(ValueRelations.ValueInfo info){
				return new FunDef(Void.TYPE,
				                  new Arg(int.class, "newValue"))
					       .getOverride(info, SetterI.class);
			}
			void setValue(IOStruct.Instance target, int newValue);
		}
		
		String target() default "";
	}
	
	@Retention(RUNTIME)
	public @interface Size{
		
		interface Sizer<T>{
			@Nullable
			static <T> IOStruct.Size.Sizer<T> get(ValueRelations.ValueInfo info, Class<T> valueType){
				return new FunDef(long.class,
				                  new Arg(valueType, "value"))
					       .getOverride(info, IOStruct.Size.Sizer.class);
			}
			long mapSize(IOStruct.Instance target, T value);
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

//	@Target(FIELD)
//	@Retention(RUNTIME)
//	public @interface PointerValue{
//		int index();
//
//		String ptrValName() default "";
//	}
	
	public static class Instance{
		
		public abstract static class Contained extends Instance{
			
			public Contained(){ }
			public Contained(IOStruct struct){
				super(struct);
			}
			
			protected abstract RandomIO getStructSourceIO() throws IOException;
			
			public void readStruct() throws IOException{
				try(var buff=getStructSourceIO()){
					readStruct(buff);
				}
			}
			
			public void writeStruct() throws IOException{
				writeStruct(false);
			}
			
			public void writeStruct(boolean trimAfterWrite) throws IOException{
				try(var buff=getStructSourceIO()){
					buff.ensureCapacity(buff.getPos()+this.getInstanceSize());
					writeStruct(buff);
					if(trimAfterWrite) buff.trim();
				}
				if(DEBUG_VALIDATION){
					validateWrittenData();
				}
			}
			
			public void validateWrittenData() throws IOException{
				try(var buff=getStructSourceIO()){
					validateWrittenData(buff);
				}
			}
		}
		
		private final IOStruct struct;
		
		public Instance(){
			this.struct=getInstance(this.getClass());
		}
		
		public Instance(IOStruct struct){
			this.struct=struct;
		}
		
		
		public void readStruct(ContentReader in) throws IOException{
			boolean shouldClose=false;
			
			ContentReader buff;
			if(ContentReader.isDirect(in)) buff=in;
			else if(struct.getKnownSize().isPresent()){
				buff=in.bufferExactRead(struct.requireKnownSize());
				shouldClose=true;
			}else if(struct.getMinimumSize()<=2) buff=in;
			else buff=new ContentInputStream.Joining2(new ContentInputStream.BA(in.readInts1((int)struct.getMinimumSize())), in);
			
			
			try{
				for(var v : struct.variableIter){
					try{
						if(DEBUG_VALIDATION){
							if(v instanceof FixedSize f){
								var siz=f.getSize();
								try(ContentReader vbuf=buff.bufferExactRead(siz, (w, e)->new IOException(this.getClass().getName()+" Var \""+v.name+"\" "+w+"/"+e))){
									v.read(this, vbuf);
								}
							}else{
								v.read(this, buff);
							}
						}else{
							v.read(this, buff);
						}
					}catch(IOException e){
						throw new IOException("Failed to read variable "+v+" in "+struct+" from "+buff, e);
					}
				}
			}finally{
				if(shouldClose) buff.close();
			}
		}
		
		public void writeStruct(ContentWriter out) throws IOException{
			boolean shouldClose=false;
			
			ContentWriter buff;
			if(ContentWriter.isDirect(out)) buff=out;
			else{
				buff=out.bufferExactWrite(getInstanceSize());
				shouldClose=true;
			}
			
			try{
				if(DEBUG_VALIDATION){
					for(var v : struct.variableIter){
						var size=v.mapSize(this);
						try(ContentWriter vbuf=buff.bufferExactWrite(size, (written, expected)->new IOException(this.getClass().getName()+" Var \""+v.name+"\" written/expected "+written+"/"+expected))){
							v.write(this, vbuf);
						}
					}
				}else{
					for(var v : struct.variableIter){
						v.write(this, buff);
					}
				}
			}finally{
				if(shouldClose) buff.close();
			}
		}
		
		public long getInstanceSize(){
			var known=struct.getKnownSize();
			if(known.isPresent()) return known.getAsLong();
			
			long sum=0;
			for(int i=struct.variableIter.length-1;i>=0;i--){
				var v=struct.variableIter[i];
				
				sum+=FixedSize.getSizeUnknown(this, v);
				
				Offset off=v.getKnownOffset();
				if(off!=null){
					sum+=off.getOffset();
					break;
				}
			}
			
			return sum;
		}
		
		public void validateWrittenData(ContentReader in) throws IOException{
			var siz=getInstanceSize();
			var bb =new ByteArrayOutputStream(Math.toIntExact(siz));
			writeStruct(SimpleContentWriter.pass(bb));
			
			if(bb.size()!=siz){
				throw new MalformedObjectException("Object declared size of "+siz+" but wrote "+bb.size()+" bytes");
			}
			
			var arr=bb.toByteArray();
			
			byte[] real=new byte[arr.length];
			
			int len=real.length;
			
			int n=0;
			while(n<len){
				int count=in.read(real, n, len-n);
				if(count<0){
					real=Arrays.copyOf(real, n);
					break;
				}
				n+=count;
			}
			
			if(Arrays.equals(arr, real)) return;
			
			if(!DEBUG_VALIDATION) throw new OutOfSyncDataException();
			
			Function<byte[], Map<String, String>> toMap=b->IntStream.range(0, b.length).boxed().collect(Collectors.toMap(i->""+i, i->""+b[i]));
			throw new OutOfSyncDataException("\n"+TextUtil.toTable(TextUtil.toString(this), toMap.apply(arr), toMap.apply(real)));
		}
		
		public IOStruct structType(){
			return struct;
		}
		
		@Override
		public String toString(){
			return struct.variables.stream().map(var->var.toString(this))
			                       .map(e->e.getKey()+": "+TextUtil.toString(e.getValue()))
			                       .collect(joining(", ", getClass().getSimpleName()+"{", "}"));
		}
		public String toShortString(){
			return getClass().getSimpleName()+toTableString();
		}
		public String toTableString(){
			return struct.variables.stream().map(var->var.toString(this))
			                       .filter(e->{
				                       if(e.getValue() instanceof Boolean b) return b;
				                       if(e.getValue() instanceof Number n) return n.longValue()!=0;
				                       if(e.getValue() instanceof INumber n) return n.getValue()!=0;
				                       return e.getValue()!=null;
			                       })
			                       .map(e->{
				                       if(e.getValue() instanceof Boolean b) return e.getKey();
				                       return e.getKey()+": "+TextUtil.toString(e.getValue());
			                       })
			                       .collect(joining(", ", "{", "}"));
		}
		
		public Offset calcVarOffset(int index){
			return calcVarOffset(struct.variables.get(index));
		}
		
		public Offset calcVarOffset(VariableNode<?> var){
			
			var known=var.getKnownOffset();
			if(known!=null) return known;
			
			long offset=0;
			
			for(VariableNode<?> node : struct.variableIter){
				if(node==var) return Offset.fromBytes(offset);
				offset+=FixedSize.getSizeUnknown(this, node);
			}
			
			throw new NotImplementedException();
		}
	}
	
	private static final Map<Class<?>, IOStruct> CACHE=new HashMap<>();
	
	public static IOStruct thisClass(){
		Class<?> type=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                         .walk(s->s.skip(1)
		                                   .findFirst()
		                                   .orElseThrow()
		                                   .getDeclaringClass()
		                         );
		return getInstance((Class<? extends Instance>)type);
	}
	
	public static IOStruct getInstance(Class<? extends Instance> instanceClass){
		return CACHE.computeIfAbsent(instanceClass, c->new IOStruct(instanceClass));
	}
	
	public final Class<? extends Instance> instanceClass;
	
	public final List<VariableNode<Object>> variables;
	
	protected final VariableNode<?>[] variableIter;
	
	private final OptionalLong knownSize;
	private final long         minimumSize;
	private final OptionalLong maximumSize;
	
	private IOStruct(Class<? extends Instance> instanceClass){
		this.instanceClass=instanceClass;
		
		variables=StructImpl.generateVariablesDefault((Class<Instance>)instanceClass);
		
		
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

//		logStruct();
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
	public VariableNode<?> varByName(String nextPtr){
		return variables.stream().filter(v->v.name.equals(nextPtr)).findAny().orElseThrow(()->new RuntimeException(nextPtr+" does not exist in "+this));
	}
}
