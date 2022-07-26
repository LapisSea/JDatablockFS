package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.io.content.ContentOutputBuilder;

public class CommandSet{
	
	public static class Builder{
		private final ContentOutputBuilder data=new ContentOutputBuilder();
		private       boolean              done;
		
		public void endFlow(){
			cmdId(ENDF);
			done=true;
		}
		
		public void skipBytes8(int bytes){
			cmdId(SKIPB_B);
			write8(bytes);
		}
		public void skipBytes32(int bytes){
			cmdId(SKIPB_I);
			
			byte[] bb=new byte[4];
			MemPrimitive.setInt(bb, 0, bytes);
			data.write(bb);
		}
		public void skipBytes64(long bytes){
			cmdId(SKIPB_L);
			
			byte[] bb=new byte[8];
			MemPrimitive.setLong(bb, 0, bytes);
			data.write(bb);
		}
		public void skipBytesUnkown(){
			cmdId(SKIPB_UNKOWN);
		}
		
		public void skipFlowIfNull(int[] indexes){
			cmdId(SKIPF_N_IF_NULL);
			write8(indexes.length);
			for(int index : indexes){
				write8(index);
			}
		}
		
		public void reference(){
			cmdId(REF);
		}
		
		private void write8(int value){
			checkRange(255, value);
			data.write(value);
		}
		private void cmdId(byte id){
			requireNotDone();
			data.write(id);
		}
		
		private void checkRange(long max, long value){
			if(value<0) throw new IllegalArgumentException("value must be positive");
			if(value>max) throw new IllegalArgumentException("value can not be larger than "+max);
		}
		private void requireNotDone(){
			if(done) throw new IllegalStateException("is done");
		}
		
		public CommandSet build(){
			if(!done) throw new IllegalStateException("not done");
			return new CommandSet(data.toByteArray());
		}
		
	}
	
	public static class CmdReader{
		private final byte[] code;
		private       int    pos;
		
		public CmdReader(byte[] code){
			this.code=code;
		}
		
		public int cmd(){
			return code[pos++];
		}
		
		public int read8(){
			return code[pos++]&0xFF;
		}
		public int read32(){
			var p=pos;
			pos+=4;
			return MemPrimitive.getInt(code, p);
		}
		public long read64(){
			var p=pos;
			pos+=8;
			return MemPrimitive.getLong(code, p);
		}
	}
	
	public static final byte ENDF           =0;
	public static final byte SKIPB_B        =1;
	public static final byte SKIPB_I        =2;
	public static final byte SKIPB_L        =3;
	public static final byte SKIPB_UNKOWN   =4;
	public static final byte SKIPF_N_IF_NULL=5;
	public static final byte REF            =6;
	
	private final byte[] code;
	
	private CommandSet(byte[] code){
		this.code=code;
	}
	
	public CmdReader reader(){
		return new CmdReader(code);
	}
	
}
