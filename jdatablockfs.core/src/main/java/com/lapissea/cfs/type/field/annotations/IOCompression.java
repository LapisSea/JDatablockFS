package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.compress.*;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IOCompression{
	enum Type{
		/**
		 * (modified) Run length encoding: Very simple and very fast. Works great for data with low noise/variation. (eg: 20 values in a row are the same)<br/>
		 * In addition, a raw block and a packed length block have been added to reduce fragility and increase compression ratio at a small sacrifice in speed.
		 */
		RLE(RlePacker::new),
		/**
		 * LZ4 fast compression is a very fast compression algorithm similar in speed to RLE but works better on higher noise/variation data.
		 */
		LZ4_FAST(Lz4Packer.Fast::new),
		/**
		 * LZ4 compression is a balance between RLE and GZIP. It is significantly slower than RLE but is also produces higher compression
		 * ratio. This is probably the right choice if type of data is unkown.
		 */
		LZ4(Lz4Packer.High::new),
		/**
		 * Your standard gzip compression. Pretty slow but has the best compression ratio.
		 */
		GZIP(GzipPacker::new),
		/**
		 * An experimental amalgamation of all previous types where all of them are ran and the smallest output is picked. This is horribly
		 * inefficient and slow and should probably not be used.
		 */
		BRUTE_BEST(BruteBestPacker::new);
		
		private Supplier<Packer> src;
		private Packer           packer;
		
		Type(Supplier<Packer> src){
			this.src=src;
		}
		
		private Packer getPacker(){
			if(packer==null) gen();
			return packer;
		}
		private void gen(){
			synchronized(this){
				if(packer!=null) return;
				packer=src.get();
				src=null;
			}
		}
		public byte[] pack(byte[] data)        {return getPacker().pack(data);}
		public byte[] unpack(byte[] packedData){return getPacker().unpack(packedData);}
	}
	
	Type value() default Type.LZ4;
	
	AnnotationLogic<IOCompression> LOGIC=new AnnotationLogic<>(){
		@Override
		public void validate(FieldAccessor<?> field, IOCompression annotation){
			if(field.getType()!=byte[].class) throw new MalformedStructLayout("IOCompression only on byte[] for now");
		}
		
		@NotNull
		@Override
		public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, IOCompression annotation){
			return List.of(new VirtualFieldDefinition<>(
				VirtualFieldDefinition.StoragePool.IO,
				IOFieldTools.makePackName(field),
				byte[].class
			));
		}
		@NotNull
		@Override
		public Set<String> getDependencyValueNames(FieldAccessor<?> field, IOCompression annotation){
			return Set.of(IOFieldTools.makePackName(field));
		}
	};
}
