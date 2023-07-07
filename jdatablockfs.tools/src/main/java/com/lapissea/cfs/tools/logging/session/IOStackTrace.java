package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.IterablePP;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class IOStackTrace extends IOInstance.Managed<IOStackTrace>{
	
	private enum IndexCmd{
		PUSH(-1),
		SET_CLASS_LOADER_NAME(0),
		SET_MODULE_NAME(1), SET_MODULE_VERSION(2),
		SET_CLASS_NAME(3), SET_METHOD_NAME(4),
		SET_FILE_NAME(5), SET_FILE_EXTENSION(5);
		
		private final int idx;
		
		private static final List<IndexCmd> CMDS     = List.of(values());
		private static final int            BUF_SIZE = CMDS.stream().mapToInt(e -> e.idx).max().orElseThrow() + 1;
		IndexCmd(int idx){ this.idx = idx; }
	}
	
	@Def.ToString(name = false, fNames = false)
	@Def.Order({"classLoaderNameId", "moduleNameId", "moduleVersionId", "classNameId", "methodNameId", "fileNameId", "lineNumber"})
	interface Element extends IOInstance.Def<Element>{
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int classLoaderNameId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int moduleNameId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int moduleVersionId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int classNameId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int methodNameId();
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		int fileNameId();
		@IODependency.VirtualNumSize
		int lineNumber();
		
		static Element of(int classLoaderNameId, int moduleNameId, int moduleVersionId, int classNameId, int methodNameId, int fileNameId, int lineNumber){
			class Constr{
				private static final MethodHandle VAL = IOInstance.Def.constrRef(Element.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
			}
			
			try{
				return (Element)Constr.VAL.invoke(classLoaderNameId, moduleNameId, moduleVersionId, classNameId, methodNameId, fileNameId, lineNumber);
			}catch(Throwable ex){
				throw UtilL.uncheckedThrow(ex);
			}
		}
		static Element of(StringsIndex maker, StackTraceElement e) throws IOException{
			return of(
				maker.make(e.getClassLoaderName()),
				maker.make(e.getModuleName()), maker.make(e.getModuleVersion()),
				maker.make(e.getClassName()), maker.make(e.getMethodName()),
				maker.make(e.getFileName()), e.getLineNumber()
			);
		}
		
		default StackTraceElement unpack(IOList<String> strings) throws IOException{
			return new StackTraceElement(
				StringsIndex.get(strings, classLoaderNameId()),
				StringsIndex.get(strings, moduleNameId()), StringsIndex.get(strings, moduleVersionId()),
				StringsIndex.get(strings, classNameId()), StringsIndex.get(strings, methodNameId()),
				StringsIndex.get(strings, fileNameId()), lineNumber()
			);
		}
	}
	
	@IOValue
	private List<IndexCmd>   commands;
	@IOValue
	private List<NumberSize> sizes;
	@IOValue
	private byte[]           numbers;
	
	@IOValue
	@IODependency.VirtualNumSize
	@IOValue.Unsigned
	private int head;
	@IOValue
	@IODependency.VirtualNumSize
	@IOValue.Unsigned
	private int ignoredFrames;
	
	private static final class CmdSequenceBuilder{
		
		private final List<IndexCmd>       commands = new ArrayList<>();
		private final List<NumberSize>     sizes    = new ArrayList<>();
		private final ContentOutputBuilder numbers  = new ContentOutputBuilder();
		
		private final StringsIndex maker;
		private final int[]        interBuf = new int[IndexCmd.BUF_SIZE];
		
		private CmdSequenceBuilder(StringsIndex maker, StackTraceElement[] frames) throws IOException{
			this.maker = maker;
			for(var e : frames){
				diffString(IndexCmd.SET_CLASS_LOADER_NAME, e.getClassLoaderName());
				diffString(IndexCmd.SET_MODULE_NAME, e.getModuleName());
				diffString(IndexCmd.SET_MODULE_VERSION, e.getModuleVersion());
				diffString(IndexCmd.SET_CLASS_NAME, e.getClassName());
				diffString(IndexCmd.SET_METHOD_NAME, e.getMethodName());
				
				var fileName = e.getFileName();
				var dotIdx   = fileName == null? -1 : fileName.indexOf('.');
				if(dotIdx == -1){
					diffString(IndexCmd.SET_FILE_NAME, null);
				}else{
					var justName      = fileName.substring(0, dotIdx);
					var className     = e.getClassName();
					var fromClassName = className.substring(className.lastIndexOf('.') + 1);
					if(!justName.equals(fromClassName)){
						diffString(IndexCmd.SET_FILE_NAME, fileName);
					}else{
						var extension = fileName.substring(dotIdx + 1);
						diffString(IndexCmd.SET_FILE_EXTENSION, extension);
					}
				}
				pushCmd(IndexCmd.PUSH, e.getLineNumber(), false);
			}
		}
		
		private void diffString(IndexCmd cmd, String val) throws IOException{
			var idx = maker.make(val);
			if(interBuf[cmd.idx] != idx){
				interBuf[cmd.idx] = idx;
				pushCmd(cmd, idx, true);
			}
		}
		
		private void pushCmd(IndexCmd cmd, int val, boolean unsigned) throws IOException{
			if(cmd.idx != -1){
				interBuf[cmd.idx] = val;
			}
			
			commands.add(cmd);
			var siz = NumberSize.bySize(val, unsigned);
			sizes.add(siz);
			siz.writeInt(numbers, val, unsigned);
		}
		
	}
	
	public IOStackTrace(){ }
	public IOStackTrace(StringsIndex maker, Throwable ex) throws IOException{
		head = maker.make(ex.toString());
		
		var ignorePackages = Set.of(MemoryData.class.getPackageName(), IOStackTrace.class.getPackageName());
		var rawFrames      = ex.getStackTrace();
		var frames = Arrays.stream(rawFrames).dropWhile(f -> {
			var pkg = f.getClassName().substring(0, f.getClassName().lastIndexOf('.'));
			return ignorePackages.contains(pkg);
		}).toArray(StackTraceElement[]::new);
		ignoredFrames = rawFrames.length - frames.length;

//		LogUtil.println(ignorePackages);
//		for(int i = 0; i<frames.length; i++){
//			var f   = frames[i];
//			var pkg = f.getClassName().substring(0, f.getClassName().lastIndexOf('.'));
//
//			LogUtil.println(i, "\t", ignorePackages.contains(pkg), "\t", f);
//		}
//
//		System.exit(0);
		
		var b = new CmdSequenceBuilder(maker, frames);
		
		this.commands = b.commands;
		this.sizes = b.sizes;
		this.numbers = b.numbers.toByteArray();

//		var els = frames().collectToList();
//
//		var actual = new ArrayList<Element>();
//		for(var e : frames){
//			actual.add(Element.of(maker, e));
//		}
//
//		if(!els.equals(actual)){
//			throw new RuntimeException("\n" + els + "\n" + actual);
//		}
	}
	
	private IterablePP<Element> frames(){
		return () -> new Iterator<>(){
			private static final int[] IDX_MAP = IndexCmd.CMDS.stream().mapToInt(IndexCmd.CMDS::indexOf).toArray();
			
			private final int[] interBuf = new int[IndexCmd.BUF_SIZE];
			
			private final Iterator<IndexCmd> cmds = commands.iterator();
			private final Iterator<NumberSize> sizs = sizes.iterator();
			private final ContentInputStream.BA nums = new ContentInputStream.BA(numbers);
			
			@Override
			public boolean hasNext(){
				return cmds.hasNext();
			}
			@Override
			public Element next(){
				try{
					IndexCmd cmd;
					while(true){
						cmd = cmds.next();
						if(cmd == IndexCmd.PUSH) break;
						int idx = IDX_MAP[cmd.ordinal()];
						var siz = sizs.next();
						interBuf[idx] = siz.readInt(nums);
					}
					
					return Element.of(interBuf[0], interBuf[1], interBuf[2], interBuf[3], interBuf[4], interBuf[5], interBuf[6]);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		};
	}
	
	@Override
	public String toString(){
		var res = new StringBuilder();
		res.append(head);
		for(var frame : frames()){
			res.append("\n\t").append(frame);
		}
		return res.toString();
	}
	public String toString(IOList<String> strings) throws IOException{
		var res = new StringBuilder();
		res.append(StringsIndex.get(strings, head));
		for(var frame : frames()){
			res.append("\n\t").append(frame.unpack(strings));
		}
		return res.toString();
	}
}
