package com.lapissea.fsf.endpoint;

import com.lapissea.util.NotImplementedException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

@SuppressWarnings("AutoBoxing")
public interface IdentifierIO<Identifier>{
	
	IdentifierIO<String> STRING=new IdentifierIO<>(){
		
		class Type{
			final Function<String, ByteBuffer> encoder;
			final Function<ByteBuffer, String> decoder;
			
			Type(Function<String, ByteBuffer> encoder, Function<ByteBuffer, String> decoder){
				this.encoder=encoder;
				this.decoder=decoder;
			}
		}
		
		
		class CharEnc extends Type{
			
			CharEnc(Charset type){
				super(s->{
					try{
						return type.newEncoder().encode(CharBuffer.wrap(s));
					}catch(CharacterCodingException e){
						return null;
					}
				}, bb->type.decode(bb).toString());
			}
		}
		
		private final Type[] coders={
			/*new Type(str->{
				try{
					return Base64.getEncoder().encode(StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(str)));
				}catch(CharacterCodingException e){
					return null;
				}
			}, bb->StandardCharsets.UTF_8.decode(Base64.getDecoder().decode(bb)).toString()),*/
			new CharEnc(StandardCharsets.UTF_8),
			new CharEnc(StandardCharsets.UTF_16)
		};
		
		@Override
		public byte[] write(String s){
			s=sanitize(s);
			ByteBuffer result;
			int        id;
			encode:
			{
				for(int i=0;i<coders.length;i++){
					Type t=coders[i];
					result=t.encoder.apply(s);
					if(result!=null){
						id=i;
						break encode;
					}
				}
				throw new RuntimeException("Unable to encode: \""+s+"\"");
			}
			byte[] bb=new byte[result.remaining()+1];
			bb[0]=(byte)id;
			System.arraycopy(result.array(), 0, bb, 1, result.limit());
			return bb;
		}
		
		@Override
		public String read(byte[] data){
			int id=data[0];
			return coders[id].decoder.apply(ByteBuffer.wrap(data, 1, data.length-1));
		}
		
		@Override
		public int size(String s){
			return write(s).length;//TODO: Performance - try to avoid this?
		}
		
		@Override
		public String defaultVal(){
			return "";
		}
		
		@Override
		public boolean isEmpty(String path){
			return path.isEmpty();
		}
		
		private String sanitize(String path){
			if(path==null) return "";
			return path.replace('\\', '/');
		}
		
		private int firstSplit(String path){
			return path.indexOf('/');
		}
		
		private int lastSplit(String path){
			return path.lastIndexOf('/');
		}
		
		private String getLast(String path, int pos){
			return pos==-1?path:path.substring(pos+1);
		}
		
		@Override
		public String getLast(String path){
			var p=sanitize(path);
			return getLast(p, lastSplit(p));
		}
		
		private String trimLast(String path, int pos){
			return pos==-1?"":path.substring(0, pos);
		}
		
		@Override
		public String trimLast(String path){
			var p=sanitize(path);
			return trimLast(p, lastSplit(p));
		}

//		@Override
//		public Split<String> splitLast(String path){
//			var p  =sanitize(path);
//			int pos=lastSplit(p);
//			return new Split<>(trimLast(p, pos), getLast(p, pos));
//		}
		
		private String getFirst(String path, int pos){
			return pos==-1?"":path.substring(0, pos);
		}
		
		@Override
		public String getFirst(String path){
			var p=sanitize(path);
			return getFirst(p, firstSplit(p));
		}
		
		@Override
		public String trimFirst(String path){
			throw NotImplementedException.infer();//TODO: implement .trimStart()
		}
	};
	
	IdentifierIO<Integer> INT=new IdentifierIO<>(){
		ThreadLocal<ByteBuffer> bbs=ThreadLocal.withInitial(()->ByteBuffer.allocate(Integer.BYTES));
		
		@Override
		public byte[] write(Integer val){
			return bbs.get()
			          .putInt(0, val)
			          .array()
			          .clone();
		}
		
		@Override
		public Integer read(byte[] data){
			return bbs.get()
			          .put(0, data)
			          .getInt(0);
		}
		
		@Override
		public int size(Integer val){
			return Integer.BYTES;
		}
		
		@Override
		public Integer defaultVal(){
			return 0;
		}
		
		@Override
		public boolean isEmpty(Integer path){
			return path==null;
		}
		
		@Override
		public Integer getLast(Integer path){
			return path;
		}
		
		@Override
		public Integer trimLast(Integer path){
			return null;
		}
		
		@Override
		public Integer getFirst(Integer path){
			return null;
		}
		
		@Override
		public Integer trimFirst(Integer path){
			return path;
		}
	};
	
	IdentifierIO<Long> LONG=new IdentifierIO<>(){
		ThreadLocal<ByteBuffer> bbs=ThreadLocal.withInitial(()->ByteBuffer.allocate(Long.BYTES));
		
		@Override
		public byte[] write(Long val){
			return bbs.get()
			          .putLong(0, val)
			          .array()
			          .clone();
		}
		
		@Override
		public Long read(byte[] data){
			return bbs.get()
			          .put(0, data)
			          .getLong(0);
		}
		
		@Override
		public int size(Long val){
			return Long.BYTES;
		}
		
		@Override
		public Long defaultVal(){
			return 0L;
		}
		
		@Override
		public boolean isEmpty(Long path){
			return path==null;
		}
		
		@Override
		public Long getLast(Long path){
			return path;
		}
		
		@Override
		public Long trimLast(Long path){
			return null;
		}
		
		@Override
		public Long getFirst(Long path){
			return null;
		}
		
		@Override
		public Long trimFirst(Long path){
			return path;
		}
	};
	
	IdentifierIO<UUID> UUID=new IdentifierIO<>(){
		ThreadLocal<ByteBuffer> bbs=ThreadLocal.withInitial(()->ByteBuffer.allocate(128/Byte.SIZE));
		
		@Override
		public byte[] write(UUID val){
			return bbs.get()
			          .position(0)
			          .putLong(val.getMostSignificantBits())
			          .putLong(val.getLeastSignificantBits())
			          .array()
			          .clone();
		}
		
		@Override
		public UUID read(byte[] data){
			var bb=bbs.get().put(0, data);
			return new UUID(bb.getLong(), bb.getLong());
		}
		
		@Override
		public int size(UUID val){
			return 128/Byte.SIZE;
		}
		
		@Override
		public UUID defaultVal(){
			return new UUID(0, 0);
		}
		
		@Override
		public boolean isEmpty(UUID path){
			return path==null;
		}
		
		@Override
		public UUID getLast(UUID path){
			return path;
		}
		
		@Override
		public UUID trimLast(UUID path){
			return null;
		}
		
		@Override
		public UUID getFirst(UUID path){
			return null;
		}
		
		@Override
		public UUID trimFirst(UUID path){
			return path;
		}
	};
	
	byte[] write(Identifier identifier);
	
	Identifier read(byte[] data);
	
	int size(Identifier identifier);
	
	/////////////////////////////////////////////
	
	Identifier defaultVal();
	
	
	boolean isEmpty(Identifier path);
	
	/////////////////////////////////////////////
	
	/**
	 * Returns last part of the path. a/b/c -> c
	 */
	Identifier getLast(Identifier path);
	
	/**
	 * Returns everything but the last part of the path. a/b/c -> a/b
	 */
	Identifier trimLast(Identifier path);
	
	class Split<Identifier>{
		public final Identifier trail;
		public final Identifier target;
		
		public Split(Identifier trail, Identifier target){
			this.trail=trail;
			this.target=target;
		}
	}
	
	/**
	 * Splits the path before the last part. a/b/c -> (a/b, c)
	 */
	default Split<Identifier> splitLast(Identifier path){
		return new Split<>(trimLast(path), getLast(path));
	}
	
	/////////////////////////////////////////////
	
	/**
	 * Returns the first part of the path. a/b/c -> a
	 */
	Identifier getFirst(Identifier path);
	
	/**
	 * Returns everything but the first part of the path. a/b/c -> b/c
	 */
	Identifier trimFirst(Identifier path);
	
	/**
	 * Splits the path after the first part. a/b/c -> (a, b/c)
	 */
	default Split<Identifier> splitFirst(Identifier path){
		return new Split<>(getFirst(path), trimFirst(path));
	}
}
