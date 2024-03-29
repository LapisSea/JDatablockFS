package com.lapissea.dfs.type.field.annotations;

import com.lapissea.dfs.io.compress.BruteBestPacker;
import com.lapissea.dfs.io.compress.GzipPacker;
import com.lapissea.dfs.io.compress.Lz4Packer;
import com.lapissea.dfs.io.compress.Packer;
import com.lapissea.dfs.io.compress.RlePacker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Supplier;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IOCompression{
	enum Type{
		/**
		 * (Modified) Run length encoding: Very simple and very fast. Works great for data with low noise/variation. (Eg: 20 values in a row are the same)<br/>
		 * In addition, a raw block and a packed length block have been added to reduce fragility and increase compression ratio at a small sacrifice in speed.
		 */
		RLE(RlePacker::new),
		/**
		 * LZ4 fast compression is a very fast compression algorithm similar in speed to RLE but works better on higher noise/variation data.
		 */
		LZ4_FAST(Lz4Packer.Fast::new),
		/**
		 * LZ4 compression is a balance between RLE and GZIP. It is significantly slower than RLE but is also produces a higher compression
		 * ratio. This is probably the right choice if the type of data is unknown.
		 */
		LZ4(Lz4Packer.High::new),
		/**
		 * Your standard gzip compression. Pretty slow but has the best compression ratio.
		 */
		GZIP(GzipPacker::new),
		/**
		 * An experimental amalgamation of all previous types where all of them are run and the smallest output is picked.
		 * This is horribly inefficient and slow and should probably not be used.
		 */
		BRUTE_BEST(BruteBestPacker::new);
		
		private Supplier<Packer> src;
		private Packer           packer;
		
		Type(Supplier<Packer> src){
			this.src = src;
		}
		
		private Packer getPacker(){
			if(packer == null) gen();
			return packer;
		}
		private void gen(){
			synchronized(this){
				if(packer != null) return;
				packer = src.get();
				src = null;
			}
		}
		public byte[] pack(byte[] data)        { return getPacker().pack(data); }
		public byte[] unpack(byte[] packedData){ return getPacker().unpack(packedData); }
	}
	
	Type value() default Type.LZ4;
}
