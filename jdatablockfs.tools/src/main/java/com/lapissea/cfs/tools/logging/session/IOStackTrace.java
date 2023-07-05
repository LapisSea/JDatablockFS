package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.ClosableLock;
import com.lapissea.cfs.utils.IterablePP;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class IOStackTrace extends IOInstance.Managed<IOStackTrace>{
	
	public record StringsMaker(IOList<String> data, Map<String, Integer> reverseIndex, ClosableLock lock){
		
		public static String get(IOList<String> strings, int head) throws IOException{
			if(head == 0) return null;
			return strings.get(head - 1);
		}
		
		public StringsMaker(IOList<String> data) throws IOException{
			this(data, new HashMap<>(Math.toIntExact(data.size())), ClosableLock.reentrant());
			for(int i = 0; i<data.size(); i++){
				reverseIndex.put(data.get(i), i++);
			}
		}
		
		private int make(String val) throws IOException{
			if(val == null) return 0;
			
			try(var ignored = lock.open()){
				var existing = reverseIndex.get(val);
				if(existing != null) return existing;
				
				int newId = Math.toIntExact(data.size() + 1);
				data.add(val);
				reverseIndex.put(val, newId);
				return newId;
			}
		}
	}
	
	enum IndexCmd{
		PUSH,
		SET_CLASS_LOADER_NAME,
		SET_MODULE_NAME, SET_MODULE_VERSION,
		SET_CLASS_NAME, SET_METHOD_NAME,
		SET_FILE_NAME, SET_LINE_NUMBER;
		
		private static final IndexCmd[] CMD_SETS = {
			SET_CLASS_LOADER_NAME,
			SET_MODULE_NAME, SET_MODULE_VERSION,
			SET_CLASS_NAME, SET_METHOD_NAME,
			SET_FILE_NAME, SET_LINE_NUMBER
		};
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
		static Element of(StringsMaker maker, StackTraceElement e) throws IOException{
			return of(
				maker.make(e.getClassLoaderName()),
				maker.make(e.getModuleName()), maker.make(e.getModuleVersion()),
				maker.make(e.getClassName()), maker.make(e.getMethodName()),
				maker.make(e.getFileName()), e.getLineNumber()
			);
		}
		
		default StackTraceElement unpack(IOList<String> strings) throws IOException{
			return new StackTraceElement(
				StringsMaker.get(strings, classLoaderNameId()),
				StringsMaker.get(strings, moduleNameId()), StringsMaker.get(strings, moduleVersionId()),
				StringsMaker.get(strings, classNameId()), StringsMaker.get(strings, methodNameId()),
				StringsMaker.get(strings, fileNameId()), lineNumber()
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
	
	public IOStackTrace(){ }
	public IOStackTrace(StringsMaker maker, Throwable ex) throws IOException{
		head = maker.make(ex.toString());
		var frames = ex.getStackTrace();
		
		int[] interBuf = new int[IndexCmd.CMD_SETS.length];
		
		var commands = new ArrayList<IndexCmd>();
		var sizes    = new ArrayList<NumberSize>();
		var numbers  = new ContentOutputBuilder();
		
		for(var e : frames){
			var strs = new String[]{
				e.getClassLoaderName(),
				e.getModuleName(), e.getModuleVersion(),
				e.getClassName(), e.getMethodName(),
				e.getFileName()
			};
			
			for(int i = 0; i<IndexCmd.CMD_SETS.length; i++){
				var cmd = IndexCmd.CMD_SETS[i];
				var val = cmd == IndexCmd.SET_LINE_NUMBER?
				          e.getLineNumber() :
				          maker.make(strs[i]);
				if(interBuf[i] != val){
					interBuf[i] = val;
					commands.add(cmd);
					var siz = NumberSize.bySize(val);
					sizes.add(siz);
					siz.writeInt(numbers, val);
				}
			}
			
			commands.add(IndexCmd.PUSH);
		}
		
		this.commands = commands;
		this.sizes = sizes;
		this.numbers = numbers.toByteArray();

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
			private static final int[] IDX_MAP = Arrays.stream(IndexCmd.values()).mapToInt(e -> Arrays.asList(IndexCmd.CMD_SETS).indexOf(e)).toArray();
			
			private final int[] interBuf = new int[IndexCmd.CMD_SETS.length];
			
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
		res.append(StringsMaker.get(strings, head));
		for(var frame : frames()){
			res.append("\n\t").append(frame.unpack(strings));
		}
		return res.toString();
	}
}
