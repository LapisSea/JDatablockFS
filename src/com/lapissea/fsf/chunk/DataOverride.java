package com.lapissea.fsf.chunk;

import com.lapissea.fsf.SelfSizedNumber;
import com.lapissea.fsf.io.ContentReader;
import com.lapissea.fsf.io.serialization.Content;
import com.lapissea.fsf.io.serialization.FileObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static com.lapissea.util.UtilL.*;

public class DataOverride extends FileObject.FullLayout<DataOverride>{
	
	public enum Type{
		FULL,
		DIFFERENTIAL,
		INCREMENTAL,
		GAY
	}
	
	private static class OverrideRange extends FileObject.FullLayout<OverrideRange>{
		private SelfSizedNumber from, to;
		private byte[] data;
		
		public OverrideRange(){
			super(sequenceBuilder(
				new FileObject.ObjDef<>(o->o.from, (o, t)->o.from=t, o->new SelfSizedNumber()),
				new FileObject.ObjDef<>(o->o.to, (o, t)->o.to=t, o->new SelfSizedNumber()),
				new FileObject.ContentDef<>(Content.BYTE_ARRAY, o->o.data, (o, t)->o.data=t)
			                     ));
		}
	}
	
	private Type            type;
	private OverrideRange[] ranges;
	
	public DataOverride(){
		super(sequenceBuilder(
			new FileObject.SingleEnumDef<>(Type.class, o->o.type, (o, t)->o.type=t),
			new FileObject.InlineArrayDef<>(o->o.ranges, (o, t)->o.ranges=t, o->new OverrideRange())
		                     ));
	}
	
	public ContentReader resolveStream(ContentReader originalData){
		return new ContentReader(){
			private final byte[] buf=new byte[8];
			
			@Override
			public byte[] contentBuf(){
				return buf;
			}
			
			long bytesRead;
			ByteArrayInputStream insert;
			
			private void scanInsert() throws IOException{
				for(OverrideRange range : ranges){
					if(range.from.equals(bytesRead)){
						insert=new ByteArrayInputStream(range.data);
						
						long   ignoreRange=range.to.getValue()-range.from.getValue();
						byte[] chunk      =new byte[(int)Math.min(1024, ignoreRange)];
						long   remaining  =ignoreRange;
						while(remaining>0){
							remaining-=originalData.read(chunk, 0, (int)Math.min(remaining, chunk.length));
						}
					}
				}
			}
			
			@Override
			public int read() throws IOException{
				
				if(insert==null) scanInsert();
				
				if(insert!=null){
					int b=insert.read();
					if(b==-1){
						insert=null;
						return read();
					}
					
					return b;
				}
				
				
				bytesRead++;
				return originalData.read();
			}
			
			@Override
			public int read(byte[] b, int off, int len) throws IOException{
				
				if(TRUE()) throw null;//TODO
				
				for(int i=off;i<len+off;i++){
					b[i]=(byte)read();
				}
				return off;
			}
		};
	}
}
