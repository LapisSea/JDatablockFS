package com.lapissea.fsf;

import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.fsf.chunk.MutableChunkPointer;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.collections.SparsePointerList;
import com.lapissea.fsf.collections.fixedlist.headers.SizedNumber;
import com.lapissea.fsf.endpoint.IdentifierIO;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

@SuppressWarnings("unchecked")
public class Folder<Identifier> extends FileObject.FullLayout<Folder<Identifier>>{
	
	private static <T extends FileObject, Id> IOList<T> getList(Header<Id> header, ChunkPointer ptr, BiFunction<Header<Id>, UnsafeConsumer<T, IOException>, T> makeElement){
		try{
			var chunk=ptr.dereference(header);
			return new SparsePointerList<>(setter->makeElement.apply(header, setter), chunk);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	private static <T extends FileObject, Id> ObjDef<Folder<Id>, SelfSizedNumber> defList(Function<Folder<Id>, Folder<Id>.ListValue<?>> getter){
		return new ObjDef<>(
			p->ChunkPointer.mapNullable(getter.apply(p).getPtr(), SelfSizedNumber::new),
			(p, v)->getter.apply(p).setPtr(v),
			(Supplier<SelfSizedNumber>)SelfSizedNumber::new
		);
	}
	
	public static <T> List<Chunk> init(Header<T> header) throws IOException{
		var folder=new Folder<>(header, e->{});
		folder.children.setPtr(folder.initList());
		folder.files.setPtr(folder.initList());
		
		return List.of(header.aloc(folder, false));
	}
	
	private static final SegmentedObjectDef<Folder<?>> LAYOUT=(SegmentedObjectDef<Folder<?>>)(Object)FileObject.sequenceBuilder(
		new IdIODef<>(Folder::getIO, Folder::getName, Folder::setName),
		defList(f->f.children),
		defList(f->f.files)
	                                                                                                                           );
	
	private class ListValue<E extends FileObject>{
		
		private final BiFunction<Header<Identifier>, UnsafeConsumer<E, IOException>, E> makeElement;
		
		private IOList<E>    list;
		private ChunkPointer listLoc;
		
		private ListValue(BiFunction<Header<Identifier>, UnsafeConsumer<E, IOException>, E> makeElement){
			this.makeElement=makeElement;
		}
		
		private ChunkLink makeLink(long thisOff){
			return new ChunkLink(false, listLoc, thisOff, newPtr->{
				setPtr(newPtr);
				update();
			});
		}
		
		private ChunkPointer getPtr(){
			return listLoc;
		}
		
		private IOList<E> makeList(ChunkPointer ptr){
			return getList(header, ptr, makeElement);
		}
		
		private void setPtr(INumber ptr){
			listLoc=new ChunkPointer(ptr);
			if(list==null||!listLoc.equals(list.getLocation().getOffset())){
				list=ChunkPointer.mapNullable(listLoc, this::makeList);
			}
		}
	}
	
	private final Header<Identifier>                              header;
	private final UnsafeConsumer<Folder<Identifier>, IOException> update;
	
	private Identifier name;
	
	private final ListValue<Folder<Identifier>>  children=new ListValue<>(Folder::new);
	private final ListValue<FileTag<Identifier>> files   =new ListValue<>((h, setter)->new FileTag<>(h));
	
	public Folder(Header<Identifier> header, UnsafeConsumer<Folder<Identifier>, IOException> update){
		this(header, update, header.identifierIO.defaultVal());
	}
	
	public Folder(Header<Identifier> header, UnsafeConsumer<Folder<Identifier>, IOException> update, Identifier name){
		super((ObjectDef<Folder<Identifier>>)((Object)LAYOUT));
		this.header=header;
		this.update=update;
		this.name=name;
		
	}
	
	private void update() throws IOException{
		update.accept(this);
	}
	
	public boolean isRoot(){
		return getIO().isEmpty(getName());
	}
	
	private IdentifierIO<Identifier> getIO(){
		return header.identifierIO;
	}
	
	private void setName(Identifier name){
		this.name=name;
	}
	
	public Identifier getName(){
		return name;
	}
	
	private ChunkPointer initList() throws IOException{
		return SparsePointerList.init(header, new SizedNumber<>(MutableChunkPointer::new, NumberSize.BYTE, header.source::getSize), 8).get(0).reference();
	}
	
	@NotNull
	public IOList<FileTag<Identifier>> getFiles(){ return Objects.requireNonNull(files.list); }
	
	@NotNull
	public IOList<Folder<Identifier>> getChildren(){ return Objects.requireNonNull(children.list); }
	
	@NotNull
	public Optional<Folder<Identifier>> cd(@NotNull IdentifierIO<Identifier> id, @NotNull Identifier path) throws IOException{
		Objects.requireNonNull(id);
		
		if(id.isEmpty(path)) return Optional.of(this);
		
		var children=getChildren();
		if(children.isEmpty()) return Optional.empty();
		
		var start=id.getFirst(path);
		for(int i=0;i<children.size();i++){
			var child=children.getElement(i);
			
			if(child.name.equals(start)){
				return child.cd(id, id.trimFirst(path));
			}
		}
		
		return Optional.empty();
	}
	
	@NotNull
	public Optional<IOList.Ref<FileTag<Identifier>>> cdFile(@NotNull IdentifierIO<Identifier> id, @NotNull Identifier path) throws IOException{
		var folder=cd(id, id.trimLast(path));
		if(folder.isEmpty()) return Optional.empty();
		var f=folder.get();
		return f.getFile(id, id.getLast(path));
	}
	
	@NotNull
	public Optional<IOList.Ref<FileTag<Identifier>>> getFile(@NotNull IdentifierIO<Identifier> id, Identifier name) throws IOException{
		Objects.requireNonNull(id);
		
		IOList<FileTag<Identifier>> files=getFiles();
		if(files.isEmpty()) return Optional.empty();
		
		if(DEBUG_VALIDATION){
			var top=id.getLast(name);
			Assert(top.equals(name), name, top);
		}
		
		for(int i=0;i<files.size();i++){
			var file=files.getElement(i);
			if(file.getPath().equals(name)) return Optional.of(files.makeReference(i));
		}
		return Optional.empty();
	}
	
	
	private Stream<IOList<FileTag<Identifier>>> fileListsDeep(){
		return Stream.concat(
			Stream.of(getFiles()),
			getChildren().stream().flatMap(Folder::fileListsDeep)
		                    );
	}
	
	public Stream<IOList<Folder<Identifier>>> folderListsDeep(){
		return Stream.concat(
			Stream.of(getChildren()),
			getChildren().stream().flatMap(Folder::folderListsDeep)
		                    );
	}
	
	public Stream<FileTag<Identifier>> filesDeep(){
		return fileListsDeep().flatMap(Collection::stream);
	}
	
	public Stream<Folder<Identifier>> foldersDeep(){
		return folderListsDeep().flatMap(Collection::stream);
	}
	
	public Stream<ChunkLink> openReferenceStream(long thisOff){
		try{
			var cPos    =getChildren().openLinkStream(IOList.PointerConverter.getDummy()).mapToLong(l->l.sourcePos);
			var cPosIter=cPos.iterator();
			
			return Stream.of(
				Stream.of(children.makeLink(thisOff+LAYOUT.getSegmentOffset(1, this)))
				, Stream.of(files.makeLink(thisOff+LAYOUT.getSegmentOffset(2, this)))
				, getChildren().stream().flatMap(f->f.openReferenceStream(cPosIter.nextLong())).onClose(cPos::close)
				, getFiles().openLinkStream(FileTag.idConverter())
			                ).flatMap(s->s);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public String toString(){
		if(header.identifierIO.isEmpty(name)) return header.source.getName()+":";
		return TextUtil.toString(name);
	}
}
