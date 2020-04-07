package com.lapissea.fsf.io.serialization;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.SelfSizedNumber;
import com.lapissea.fsf.SmallNumber;
import com.lapissea.fsf.endpoint.IdentifierIO;
import com.lapissea.fsf.flags.FlagReader;
import com.lapissea.fsf.flags.FlagWriter;
import com.lapissea.fsf.io.ContentBuffer;
import com.lapissea.fsf.io.ContentReader;
import com.lapissea.fsf.io.ContentWriter;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.BiConsumerOL;
import com.lapissea.util.function.FunctionOL;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public abstract class FileObject{
	
	public interface ObjectDef<T>{
		void write(ContentWriter stream, T source) throws IOException;
		
		void read(ContentReader stream, T dest) throws IOException;
		
		long length(T parent);
	}
	
	public interface SegmentedObjectDef<T> extends ObjectDef<T>{
		int getSegmentCount();
		
		long getSegmentLength(int index, T source);
		
		default long getSegmentOffset(int index, T source){
			Objects.checkIndex(index, getSegmentCount());
			long sum=0;
			for(int i=0;i<index;i++){
				sum+=getSegmentLength(index, source);
			}
			return sum;
		}
		
		@Override
		default long length(T source){
			long sum=0;
			for(int i=0, j=getSegmentCount();i<j;i++){
				sum+=getSegmentLength(i, source);
			}
			return sum;
		}
	}
	
	public static class InlineArrayDef<T, V extends FileObject> implements ObjectDef<T>{
		
		private final Function<T, V[]>   getter;
		private final BiConsumer<T, V[]> setter;
		private final Function<T, V>     constructor;
		
		public InlineArrayDef(Function<T, V[]> getter, BiConsumer<T, V[]> setter, Supplier<V> constructor){
			this(getter, setter, t->constructor.get());
		}
		
		public InlineArrayDef(Function<T, V[]> getter, BiConsumer<T, V[]> setter, Function<T, V> constructor){
			this.getter=getter;
			this.setter=setter;
			this.constructor=constructor;
		}
		
		@Override
		public void write(ContentWriter stream, T source) throws IOException{
			V[] data=getter.apply(source);
			new SelfSizedNumber(data.length).write(stream);
			for(V v : data){
				v.write(stream);
			}
		}
		
		@Override
		public void read(ContentReader stream, T dest) throws IOException{
			var size=new SelfSizedNumber();
			size.read(stream);
			
			@SuppressWarnings("unchecked")
			V[] data=(V[])Array.newInstance(constructor.apply(dest).getClass(), Math.toIntExact(size.getValue()));
			
			for(int i=0;i<data.length;i++){
				data[i]=constructor.apply(dest);
				data[i].read(stream);
			}
			setter.accept(dest, data);
		}
		
		@Override
		public long length(T parent){
			V[] data=getter.apply(parent);
			return NumberSize.bySize(data.length).bytes+
			       Arrays.stream(data).mapToLong(FileObject::length).sum();
		}
	}
	
	public static class ObjDef<T, V extends FileObject> implements ObjectDef<T>{
		
		public static <T, V extends FileObject> ObjDef<T, V> finalRef(Function<T, V> getter){
			return new ObjDef<>(getter,
			                    (h, v)->{throw new ShouldNeverHappenError();},
			                    (h)->{throw new ShouldNeverHappenError();});
		}
		
		private final Function<T, V>   getter;
		private final BiConsumer<T, V> setter;
		private final Function<T, V>   constructor;
		
		public ObjDef(Function<T, V> getter, BiConsumer<T, V> setter, Supplier<V> constructor){
			this(getter, setter, t->constructor.get());
		}
		
		public ObjDef(Function<T, V> getter, BiConsumer<T, V> setter, Function<T, V> constructor){
			this.getter=getter;
			this.setter=setter;
			this.constructor=constructor;
		}
		
		@Override
		public void write(ContentWriter stream, T source) throws IOException{
			getter.apply(source).write(stream);
		}
		
		@Override
		public void read(ContentReader stream, T dest) throws IOException{
			{
				var val=getter.apply(dest);
				if(val!=null){
					val.read(stream);
					return;
				}
			}
			
			var val=constructor.apply(dest);
			val.read(stream);
			
			setter.accept(dest, val);
		}
		
		@Override
		public long length(T parent){
			return getter.apply(parent).length();
		}
	}
	
	public abstract static class WNumDef<T> implements ObjectDef<T>{
		
		private final Function<T, NumberSize> getSize;
		
		public WNumDef(Function<T, NumberSize> getSize){
			this.getSize=getSize;
		}
		
		protected NumberSize size(T parent){
			return getSize.apply(parent);
		}
		
		@Override
		public long length(T parent){
			return size(parent).bytes;
		}
	}
	
	public static class NumberDef<T> extends WNumDef<T>{
		
		private final FunctionOL<T>   getter;
		private final BiConsumerOL<T> setter;
		
		public NumberDef(NumberSize size, FunctionOL<T> getter, BiConsumerOL<T> setter){
			this(t->size, getter, setter);
		}
		
		public NumberDef(Function<T, NumberSize> getSize, FunctionOL<T> getter, BiConsumerOL<T> setter){
			super(getSize);
			this.getter=getter;
			this.setter=setter;
		}
		
		@Override
		public void write(ContentWriter stream, T source) throws IOException{
			size(source).write(stream, getter.apply(source));
		}
		
		@Override
		public void read(ContentReader stream, T dest) throws IOException{
			setter.accept(dest, size(dest).read(stream));
		}
	}
	
	public static class FlagDef<T> extends WNumDef<T>{
		
		private final BiConsumer<FlagWriter, T> getter;
		private final BiConsumer<FlagReader, T> setter;
		
		public FlagDef(BiConsumer<FlagWriter, T> getter, BiConsumer<FlagReader, T> setter){
			this(NumberSize.BYTE, getter, setter);
		}
		
		public FlagDef(NumberSize size, BiConsumer<FlagWriter, T> getter, BiConsumer<FlagReader, T> setter){
			this(t->size, getter, setter);
		}
		
		public FlagDef(Function<T, NumberSize> getSize, BiConsumer<FlagWriter, T> getter, BiConsumer<FlagReader, T> setter){
			super(getSize);
			this.getter=getter;
			this.setter=setter;
		}
		
		@Override
		public void write(ContentWriter stream, T source) throws IOException{
			FlagWriter writer=new FlagWriter(size(source));
			getter.accept(writer, source);
			writer.export(stream);
		}
		
		@Override
		public void read(ContentReader stream, T dest) throws IOException{
			FlagReader reader=FlagReader.read(stream, size(dest));
			setter.accept(reader, dest);
		}
	}
	
	public static class IdIODef<T, id> implements ObjectDef<T>{
		
		private final Function<T, IdentifierIO<id>> getIO;
		private final Function<T, id>               getter;
		private final BiConsumer<T, id>             setter;
		
		public IdIODef(Function<T, IdentifierIO<id>> getIO, Function<T, id> getter, BiConsumer<T, id> setter){
			this.getIO=getIO;
			this.getter=getter;
			this.setter=setter;
		}
		
		@Override
		public void write(ContentWriter stream, T source) throws IOException{
			Content.BYTE_ARRAY_SMALL.write(stream, getIO.apply(source).write(getter.apply(source)));
		}
		
		@Override
		public void read(ContentReader stream, T dest) throws IOException{
			setter.accept(dest, getIO.apply(dest).read(Content.BYTE_ARRAY_SMALL.read(stream)));
		}
		
		@Override
		public long length(T parent){
			var len=getIO.apply(parent).size(getter.apply(parent));
			return SmallNumber.bytes(len)+len;
		}
		
	}
	
	public static class SingleEnumDef<T, V extends Enum<V>> extends FlagDef<T>{
		
		public SingleEnumDef(Class<V> type, Function<T, V> getter, BiConsumer<T, V> setter){
			super(Arrays.stream(NumberSize.values()).filter(s->s.bytes*8 >= FlagWriter.enumBits(type)).findFirst().orElseThrow(),
			      (f, t)->f.writeEnum(getter.apply(t)).fillRestAllOne(),
			      (f, v)->{
				      V val=f.readEnum(type);
				      Assert(f.checkRestAllOne());
				      setter.accept(v, val);
			      });
		}
	}
	
	public static class ContentDef<T, V, Type extends Content<V>> implements ObjectDef<T>{
		private final Function<T, V>   getter;
		private final BiConsumer<T, V> setter;
		private final Type             type;
		
		public ContentDef(Type type, Function<T, V> getter, BiConsumer<T, V> setter){
			this.type=type;
			this.getter=getter;
			this.setter=setter;
		}
		
		@Override
		public void write(ContentWriter stream, T source) throws IOException{
			type.write(stream, getter.apply(source));
		}
		
		@Override
		public void read(ContentReader stream, T dest) throws IOException{
			setter.accept(dest, type.read(stream));
		}
		
		@Override
		public long length(T parent){
			return type.length(getter.apply(parent));
		}
	}
	
	private static final SegmentedObjectDef<Object> EMPTY_SEQUENCE=new SegmentedObjectDef<>(){
		@Override
		public int getSegmentCount(){
			return 0;
		}
		
		@Override
		public long getSegmentLength(int index, Object source){
			return 0;
		}
		
		@Override
		public void write(ContentWriter stream, Object source){ }
		
		@Override
		public void read(ContentReader stream, Object dest){ }
		
		@Override
		public long length(Object parent){
			return 0;
		}
	};
	
	private static class SequenceLayoutArrImpl<T> implements SegmentedObjectDef<T>{
		private final ObjectDef<T>[] arr;
		
		private final ContentBuffer buf=new ContentBuffer();
		
		private SequenceLayoutArrImpl(ObjectDef<T>[] arr){
			this.arr=arr;
		}
		
		@Override
		public void write(ContentWriter stream, T source) throws IOException{
			synchronized(this){
				try(var buf=this.buf.session(stream)){
					long sum=0;
					for(var value : arr){
						value.write(buf, source);
						sum+=value.length(source);
						if(DEBUG_VALIDATION){
							Assert(buf.size()==sum, source, value, buf.size(), sum);
						}
					}
					
				}
			}
		}
		
		@Override
		public void read(ContentReader stream, T dest) throws IOException{
			for(var value : arr){
				value.read(stream, dest);
			}
		}
		
		@Override
		public int getSegmentCount(){
			return arr.length;
		}
		
		@Override
		public long getSegmentLength(int index, T source){
			return arr[index].length(source);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T> SegmentedObjectDef<T> sequenceBuilder(){
		return (SegmentedObjectDef<T>)EMPTY_SEQUENCE;
	}
	
	@SafeVarargs
	public static <T> SegmentedObjectDef<T> sequenceBuilder(ObjectDef<T>... values){
		return sequenceBuilder(ArrayViewList.create(values, null));
	}
	
	@SuppressWarnings("unchecked")
	public static <T> SegmentedObjectDef<T> sequenceBuilder(List<ObjectDef<T>> values){
		if(values.isEmpty()) return sequenceBuilder();
		return new SequenceLayoutArrImpl<>(values.toArray(ObjectDef[]::new));
	}
	
	public static class FullLayout<SELF extends FullLayout<SELF>> extends FileObject{
		
		private final ObjectDef<SELF> layout;
		
		public FullLayout(ObjectDef<SELF> layout){
			this.layout=layout;
		}
		
		private SELF self(){
			return (SELF)this;
		}
		
		@Override
		public void read(ContentReader dest) throws IOException{
			layout.read(dest, self());
		}
		
		@Override
		public void write(ContentWriter dest) throws IOException{
			layout.write(dest, self());
		}
		
		@Override
		public long length(){
			return layout.length(self());
		}
	}
	
	public abstract void read(ContentReader dest) throws IOException;
	
	public abstract void write(ContentWriter dest) throws IOException;
	
	public abstract long length();
	
}
