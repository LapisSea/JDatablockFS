package com.lapissea.fsf;

import com.lapissea.util.function.BiConsumerOL;
import com.lapissea.util.function.FunctionOL;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.lapissea.util.UtilL.*;

public abstract class FileObject{
	
	public interface ObjectDef<T>{
		void write(ContentOutputStream stream, T source) throws IOException;
		
		void read(ContentInputStream stream, T dest) throws IOException;
		
		int length(T parent);
	}
	
	public interface SequenceLayout<T>{
		void write(ContentOutputStream stream, T source) throws IOException;
		
		void read(ContentInputStream stream, T dest) throws IOException;
		
		int length(T source);
	}
	
	public static class ObjDef<T, V extends FileObject> implements ObjectDef<T>{
		private final Function<T, V>   getter;
		private final BiConsumer<V, T> setter;
		private final Function<T, V>   constructor;
		
		public ObjDef(Function<T, V> getter, BiConsumer<V, T> setter, Function<T, V> constructor){
			this.getter=getter;
			this.setter=setter;
			this.constructor=constructor;
		}
		
		@Override
		public void write(ContentOutputStream stream, T source) throws IOException{
			getter.apply(source).write(stream);
		}
		
		@Override
		public void read(ContentInputStream stream, T dest) throws IOException{
			{
				var val=getter.apply(dest);
				if(val!=null){
					val.read(stream);
					return;
				}
			}
			
			var val=constructor.apply(dest);
			val.read(stream);
			
			setter.accept(val, dest);
		}
		
		@Override
		public int length(T parent){
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
		public int length(T parent){
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
		public void write(ContentOutputStream stream, T source) throws IOException{
			size(source).write(stream, getter.apply(source));
		}
		
		@Override
		public void read(ContentInputStream stream, T dest) throws IOException{
			setter.accept(dest, size(dest).read(stream));
		}
	}
	
	public static class FlagDef<T> extends WNumDef<T>{
		
		private final BiConsumer<FlagWriter, T> getter;
		private final BiConsumer<FlagReader, T> setter;
		
		public FlagDef(NumberSize size, BiConsumer<FlagWriter, T> getter, BiConsumer<FlagReader, T> setter){
			this(t->size, getter, setter);
		}
		
		public FlagDef(Function<T, NumberSize> getSize, BiConsumer<FlagWriter, T> getter, BiConsumer<FlagReader, T> setter){
			super(getSize);
			this.getter=getter;
			this.setter=setter;
		}
		
		@Override
		public void write(ContentOutputStream stream, T source) throws IOException{
			FlagWriter writer=new FlagWriter(size(source));
			getter.accept(writer, source);
			writer.export(stream);
		}
		
		@Override
		public void read(ContentInputStream stream, T dest) throws IOException{
			FlagReader reader=FlagReader.read(stream, size(dest));
			setter.accept(reader, dest);
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
		public void write(ContentOutputStream stream, T source) throws IOException{
			type.write(stream, getter.apply(source));
		}
		
		@Override
		public void read(ContentInputStream stream, T dest) throws IOException{
			setter.accept(dest, type.read(stream));
		}
		
		@Override
		public int length(T parent){
			return type.length(getter.apply(parent));
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T, Vs> SequenceLayout<T> sequenceBuilder(List<ObjectDef<T>> values){
		ObjectDef<T>[] arr=values.toArray(ObjectDef[]::new);
		
		return new SequenceLayout<>(){
			
			ByteArrayOutputStream os=new ByteArrayOutputStream();
			ContentOutputStream buf=new ContentOutputStream.Wrapp(os);
			
			@Override
			public void write(ContentOutputStream stream, T source) throws IOException{
				synchronized(this){
					os.reset();
					
					for(var value : arr){
						value.write(buf, source);
					}
					
					Assert(os.size()==length(source));
					os.writeTo(stream);
				}
			}
			
			@Override
			public void read(ContentInputStream stream, T dest) throws IOException{
				for(var value : arr){
					value.read(stream, dest);
				}
			}
			
			@Override
			public int length(T source){
				int sum=0;
				for(ObjectDef<T> v : arr){
					int length=v.length(source);
					sum+=length;
				}
				return sum;
			}
		};
	}
	
	public static class FullLayout<SELF extends FullLayout<SELF>> extends FileObject{
		
		private final SequenceLayout<SELF> layout;
		
		public FullLayout(SequenceLayout<SELF> layout){
			this.layout=layout;
		}
		
		private SELF self(){
			return (SELF)this;
		}
		
		@Override
		public void read(ContentInputStream dest) throws IOException{
			layout.read(dest, self());
		}
		
		@Override
		public void write(ContentOutputStream dest) throws IOException{
			layout.write(dest, self());
		}
		
		@Override
		public int length(){
			return layout.length(self());
		}
	}
	
	public abstract void read(ContentInputStream dest) throws IOException;
	
	public abstract void write(ContentOutputStream dest) throws IOException;
	
	public abstract int length();
	
}
