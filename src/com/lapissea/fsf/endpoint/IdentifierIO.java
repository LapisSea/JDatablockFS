package com.lapissea.fsf.endpoint;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
			new Type(str->{
				try{
					return Base64.getEncoder().encode(StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(str)));
				}catch(CharacterCodingException e){
					return null;
				}
			}, bb->StandardCharsets.UTF_8.decode(Base64.getDecoder().decode(bb)).toString()),
			new CharEnc(StandardCharsets.UTF_8),
			new CharEnc(StandardCharsets.UTF_16)
		};
		
		@Override
		public byte[] write(String s){
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
	};
	
	byte[] write(Identifier identifier);
	
	Identifier read(byte[] data);
	
	int size(Identifier identifier);
	
}
