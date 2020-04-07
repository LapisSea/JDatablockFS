package com.lapissea.fsf;

import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.util.UtilL.*;

public class FileEntry{
	
	public static class Head extends FileObject.FullLayout<Head> implements FixedLenList.ElementHead<Head, FileEntry>{
		
		private static final ObjectDef<Head> LAYOUT=new FlagDef<>((f, h)->{
			f.writeEnum(h.fileIdSize);
			f.writeEnum(h.pointerSiz);
			f.fillRestAllOne();
		}, (f, h)->{
			h.fileIdSize=f.readEnum(NumberSize.class);
			h.pointerSiz=f.readEnum(NumberSize.class);
			Assert(f.checkRestAllOne());
		});
		
		private       NumberSize fileIdSize;
		private       NumberSize pointerSiz;
		private final Header<?>  fileHeader;
		
		public Head(Header<?> fileHeader){
			this(fileHeader, NumberSize.BYTE, NumberSize.BYTE);
		}
		
		public Head(Header<?> fileHeader, NumberSize fileIdSize, NumberSize pointerSiz){
			super(LAYOUT);
			this.fileHeader=fileHeader;
			this.fileIdSize=Objects.requireNonNull(fileIdSize);
			this.pointerSiz=Objects.requireNonNull(pointerSiz);
		}
		
		@Override
		public Head copy(){
			return new Head(fileHeader,
			                fileIdSize,
			                pointerSiz);
		}
		
		@Override
		public boolean willChange(FileEntry element) throws IOException{
			return !fileIdSize.canFit(element.fileId)||
			       !pointerSiz.canFit(getFileData(element));
		}
		
		private long getFileData(FileEntry element) throws IOException{
			return element.fileData==null?fileHeader.source.getSize():element.fileData.getValue();
		}
		
		@Override
		public void update(FileEntry element) throws IOException{
			fileIdSize=fileIdSize.max(NumberSize.bySize(element.fileId));
			pointerSiz=pointerSiz.max(NumberSize.bySize(getFileData(element)));
		}
		
		@Override
		public int getElementSize(){
			return fileIdSize.bytes+
			       pointerSiz.bytes;
		}
		
		@Override
		public FileEntry newElement(){
			return new FileEntry(null, null);
		}
		
		@Override
		public void readElement(ContentInputStream src, FileEntry dest) throws IOException{
			dest.fileId=new FileID(fileIdSize.read(src));
			dest.fileData=ChunkPointer.readOrNull(pointerSiz, src);
		}
		
		@Override
		public void writeElement(ContentOutputStream dest, FileEntry src) throws IOException{
			fileIdSize.write(dest, src.fileId);
			ChunkPointer.writeNullable(pointerSiz, dest, src.fileData);
		}
		
	}
	
	public static final IOList.PointerConverter<FileEntry> CONVERTER=IOList.PointerConverter.make(FileEntry::getData, (old, ptr)->{
		old.fileData=new ChunkPointer(ptr);
		return old;
	});
	
	private FileID       fileId;
	@Nullable
	private ChunkPointer fileData;
	
	public FileEntry(FileID fileId){
		this(fileId, null);
	}
	
	public FileEntry(FileID fileId, @Nullable ChunkPointer fileData){
		this.fileId=fileId;
		this.fileData=fileData;
	}
	
	public FileID getId(){
		return fileId;
	}
	
	@Nullable
	public ChunkPointer getData(){
		return fileData;
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof FileEntry e&&
		       fileId.equals(e.fileId)&&
		       Objects.equals(fileData, e.fileData);
	}
	
	@Override
	public int hashCode(){
		int result=1;
		
		result=31*result+fileId.hashCode();
		result=31*result+(fileData==null?0:fileData.hashCode());
		
		return result;
	}
	
	public FileEntry withData(ChunkPointer data){
		return new FileEntry(fileId, data);
	}
}
