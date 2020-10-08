package test;

import com.lapissea.util.LogUtil;

record MemFrame(byte[] data, long[] ids, String[] e){
	
	private static String[] toLines(Throwable e){
		var      stack=e.getStackTrace();
		String[] lines=new String[stack.length+1];
		lines[0]=e.toString();
		for(int i=0;i<stack.length;i++){
			lines[i+1]=stack[i].toString();
		}
		return lines;
	}
	
	public MemFrame(byte[] data, long[] ids, Throwable e){
		this(data, ids, toLines(e));
	}
	
	public void printStackTrace(){
		for(String line : e){
			LogUtil.printlnEr(line);
		}
	}
	
}
