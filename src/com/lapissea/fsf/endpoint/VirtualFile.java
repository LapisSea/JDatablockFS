package com.lapissea.fsf.endpoint;

import com.lapissea.fsf.FilePointer;
import com.lapissea.fsf.Header;
import com.lapissea.fsf.io.IOInterface;
import com.lapissea.fsf.io.RandomIO;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import static com.lapissea.util.UtilL.*;

public class VirtualFile<Identifier> implements IFile<Identifier>{
	
	private FilePointer<Identifier> source;
	private IOInterface             data;
	
	public VirtualFile(FilePointer<Identifier> pointer){
		this.source=pointer;
	}
	
	@Override
	public Identifier getPath(){
		return source.getLocalPath();
	}
	
	
	private void ensureFileExistance(long initialSize) throws IOException{
		header().createFile(source.getLocalPath());
		data=header().makeFileData(source.getLocalPath(), initialSize).getFile().getFilePtr(header()).dereference(header()).io();
	}
	
	private IOInterface getData() throws IOException{
		if(data==null){
			var c=source.getFile().getFilePtr(header()).dereference(header());
			if(c!=null) data=c.io();
		}
		return data;
	}
	
	private Header<Identifier> header(){
		return source.header;
	}
	
	/**
	 * crucial to close
	 */
	private class BufferingInit extends OutputStream{
		private OutputStream out=new ByteArrayOutputStream(){
			@Override
			public void close() throws IOException{
				Assert(getData()==null);
				ensureFileExistance(wrote);
				var directOut=getData().write(clipOnEnd);
				writeTo(directOut);
				out=directOut;
			}
		};
		
		private boolean usingDirect;
		private int     wrote;
		
		private final boolean clipOnEnd;
		
		private BufferingInit(boolean clipOnEnd){
			this.clipOnEnd=clipOnEnd;
		}
		
		private void logWrite(int moreData) throws IOException{
			if(usingDirect) return;
			wrote+=moreData;//ok to add moreData before as extra space will be filled right away
			if(wrote>header().config.maxBufferingInitSize) realFileMode();
		}
		
		private void realFileMode() throws IOException{
			if(usingDirect) return;
			usingDirect=true;
			out.close();
		}
		
		@Override
		public void write(int b) throws IOException{
			logWrite(1);
			out.write(b);
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			Objects.checkFromIndexSize(off, len, b.length);
			logWrite(len);
			out.write(b, off, len);
		}
		
		@Override
		public void close() throws IOException{
			realFileMode();
			out.close();
		}
	}

//	@Override
//	public InputStream read(long offset) throws IOException{
//		var data=getData();
//		if(data==null) throw new FileNotFoundException(getPath());
//		return data.read(offset);
//	}
//
//	@Override
//	public OutputStream write(long offset) throws IOException{
//		if(getData()==null){
//			ensureFileExistance(16);
////			if(offset!=0) ensureFileExistance(16);
////			else return new BufferingInit(true);
//		}
//		return getData().write(offset, true);
//
//	}
	
	@Override
	public long getSize() throws IOException{
		var data=getData();
		return data==null?0:data.getSize();
	}
	
	@Override
	public boolean delete() throws IOException{
		if(!exists()) return false;
		data=null;
		header().deleteFile(source.getLocalPath());
		return true;
	}
	
	@Override
	public RandomIO randomIO(RandomIO.Mode mode) throws IOException{
		var io=getData().doRandom();
		return mode.canWrite?io:RandomIO.readOnly(io);
	}
	
	@Override
	public boolean rename(Identifier newId) throws IOException{
		var renamed=header().rename(getPath(), newId);
		if(!renamed) return false;
		source=Objects.requireNonNull(header().getByPath(newId));
		return true;
	}
	
	@Override
	public boolean exists() throws IOException{
		throw NotImplementedException.infer();//TODO: implement VirtualFile.exists()
	}
}
