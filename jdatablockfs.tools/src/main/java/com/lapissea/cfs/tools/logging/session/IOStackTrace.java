package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.ClosableLock;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
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
		
		static Element of(StringsMaker maker, StackTraceElement e) throws IOException{
			class Constr{
				private static final MethodHandle VAL = IOInstance.Def.constrRef(Element.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
			}
			
			try{
				return (Element)Constr.VAL.invoke(
					maker.make(e.getClassLoaderName()),
					maker.make(e.getModuleName()), maker.make(e.getModuleVersion()),
					maker.make(e.getClassName()), maker.make(e.getMethodName()),
					maker.make(e.getFileName()), e.getLineNumber()
				);
			}catch(Throwable ex){
				throw UtilL.uncheckedThrow(ex);
			}
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
	private Element[] frames;
	@IOValue
	@IODependency.VirtualNumSize
	@IOValue.Unsigned
	private int       head;
	
	public IOStackTrace(){ }
	public IOStackTrace(StringsMaker maker, Throwable e) throws IOException{
		head = maker.make(e.toString());
		var frames = e.getStackTrace();
		this.frames = new Element[frames.length];
		for(int i = 0; i<frames.length; i++){
			this.frames[i] = Element.of(maker, frames[i]);
		}
	}
	@Override
	public String toString(){
		var res = new StringBuilder();
		res.append(head);
		for(var frame : frames){
			res.append("\n\t").append(frame);
		}
		return res.toString();
	}
	public String toString(IOList<String> strings) throws IOException{
		var res = new StringBuilder();
		res.append(StringsMaker.get(strings, head));
		for(var frame : frames){
			res.append("\n\t").append(frame.unpack(strings));
		}
		return res.toString();
	}
}
