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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;

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
	
	private sealed interface FileName{
		record Raw(int id) implements FileName{ }
		
		record Ext(int id) implements FileName{ }
		
		int id();
		
		static FileName make(StringsIndex maker, StackTraceElement e) throws IOException{
			var fileName = e.getFileName();
			var dotIdx   = fileName == null? -1 : fileName.indexOf('.');
			if(dotIdx == -1) return new Raw(0);
			
			var justName  = fileName.substring(0, dotIdx);
			var className = e.getClassName();
			var cNameIdx  = className.lastIndexOf('.');
			var fromCName = cNameIdx == -1? null : className.substring(cNameIdx + 1);
			if(!justName.equals(fromCName)){
				return new Raw(maker.make(fileName));
			}
			
			var extension = fileName.substring(dotIdx + 1);
			return new Ext(maker.make(extension));
		}
		default String toStr(String className, IOList<String> strings) throws IOException{
			var base = strings.get(id());
			return switch(this){
				case Ext ignored -> {
					var fromCName = className.substring(className.lastIndexOf('.') + 1);
					yield fromCName + "." + base;
				}
				case Raw ignored -> base;
			};
		}
	}
	
	private record StackElementIndexed(
		int classLoaderNameId,
		int moduleNameId, int moduleVersionId,
		int classNameId, int methodNameId,
		FileName fileName, int lineNumber
	){
		private StackElementIndexed(StringsIndex maker, StackTraceElement e) throws IOException{
			this(
				maker.make(e.getClassLoaderName()),
				maker.make(e.getModuleName()), maker.make(e.getModuleVersion()),
				maker.make(e.getClassName()), maker.make(e.getMethodName()),
				FileName.make(maker, e), e.getLineNumber()
			);
		}
		StackTraceElement unpack(IOList<String> strings) throws IOException{
			var className = Objects.requireNonNull(StringsIndex.get(strings, classNameId));
			return new StackTraceElement(
				StringsIndex.get(strings, classLoaderNameId),
				StringsIndex.get(strings, moduleNameId), StringsIndex.get(strings, moduleVersionId),
				className, StringsIndex.get(strings, methodNameId),
				fileName.toStr(className, strings), lineNumber
			);
		}
		@Override
		public String toString(){
			return "{" +
			       classLoaderNameId + " " +
			       moduleNameId + " " + moduleVersionId + " " +
			       classNameId + " " + methodNameId + " " +
			       fileName + " " + lineNumber +
			       "}";
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
	private int head, ignoredFrames, diffBottomCount;
	
	private static final class CmdSequenceBuilder{
		
		private final List<IndexCmd>       commands = new ArrayList<>();
		private final List<NumberSize>     sizes    = new ArrayList<>();
		private final ContentOutputBuilder numbers  = new ContentOutputBuilder();
		
		private final StringsIndex maker;
		private final int[]        interBuf = new int[IndexCmd.BUF_SIZE];
		
		private CmdSequenceBuilder(StringsIndex maker, StackTraceElement[] frames) throws IOException{
			this.maker = maker;
			for(var frame : frames){
				var e = new StackElementIndexed(maker, frame);
				
				diffIdx(IndexCmd.SET_CLASS_LOADER_NAME, e.classLoaderNameId);
				diffIdx(IndexCmd.SET_MODULE_NAME, e.moduleNameId);
				diffIdx(IndexCmd.SET_MODULE_VERSION, e.moduleVersionId);
				diffIdx(IndexCmd.SET_CLASS_NAME, e.classNameId);
				diffIdx(IndexCmd.SET_METHOD_NAME, e.methodNameId);
				
				diffIdx(switch(e.fileName){
					case FileName.Ext ignored -> IndexCmd.SET_FILE_EXTENSION;
					case FileName.Raw ignored -> IndexCmd.SET_FILE_NAME;
				}, e.fileName.id());
				
				pushCmd(IndexCmd.PUSH, e.lineNumber, false);
			}
		}
		
		private void diffIdx(IndexCmd cmd, int idx) throws IOException{
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
	public IOStackTrace(StringsIndex maker, Throwable ex, int diffBottomCount) throws IOException{
		head = maker.make(ex.toString());
		this.diffBottomCount = diffBottomCount;
		
		var ignorePackages = Set.of(MemoryData.class.getPackageName(), IOStackTrace.class.getPackageName());
		var rawFrames      = ex.getStackTrace();
		var frames = Arrays.stream(rawFrames, 0, rawFrames.length - diffBottomCount).dropWhile(f -> {
			var pkg = f.getClassName().substring(0, f.getClassName().lastIndexOf('.'));
			return ignorePackages.contains(pkg);
		}).toArray(StackTraceElement[]::new);
		ignoredFrames = rawFrames.length - frames.length;
		
		var b = new CmdSequenceBuilder(maker, frames);
		
		this.commands = b.commands;
		this.sizes = b.sizes;
		this.numbers = b.numbers.toByteArray();
		
		if(DEBUG_VALIDATION) validateCmds(maker, frames);
	}
	
	private void validateCmds(StringsIndex maker, StackTraceElement[] frames) throws IOException{
		var actual   = frames().collectToList();
		var expected = new ArrayList<StackElementIndexed>();
		for(StackTraceElement frame : frames){
			expected.add(new StackElementIndexed(maker, frame));
		}
		
		if(!actual.equals(expected)){
			throw new AssertionError("expected / actual\n" + expected + "\n" + actual);
		}
	}
	
	private IterablePP<StackElementIndexed> frames(){
		return () -> new Iterator<>(){
			private static final int[] IDX_MAP = IndexCmd.CMDS.stream().mapToInt(c -> c.idx).toArray();
			
			private final int[] interBuf = new int[IndexCmd.BUF_SIZE];
			private boolean isFileNameRaw = false;
			
			private final Iterator<IndexCmd> cmds = commands.iterator();
			private final Iterator<NumberSize> sizs = sizes.iterator();
			private final ContentInputStream.BA nums = new ContentInputStream.BA(numbers);
			
			@Override
			public boolean hasNext(){
				return cmds.hasNext();
			}
			@Override
			public StackElementIndexed next(){
				try{
					IndexCmd cmd;
					int      lineNumber;
					wh:
					while(true){
						cmd = cmds.next();
						switch(cmd){
							case PUSH -> {
								var siz = sizs.next();
								lineNumber = siz.readIntSigned(nums);
								break wh;
							}
							case SET_FILE_NAME -> isFileNameRaw = true;
							case SET_FILE_EXTENSION -> isFileNameRaw = false;
						}
						int idx = IDX_MAP[cmd.ordinal()];
						var siz = sizs.next();
						interBuf[idx] = siz.readInt(nums);
					}
					var fn = isFileNameRaw? new FileName.Raw(interBuf[5]) : new FileName.Ext(interBuf[5]);
					return new StackElementIndexed(interBuf[0], interBuf[1], interBuf[2], interBuf[3], interBuf[4], fn, lineNumber);
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
