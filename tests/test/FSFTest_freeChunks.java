package test;

import com.lapissea.fsf.Renderer;
import com.lapissea.util.LogUtil;

class FSFTest_freeChunks{
	
	static{
//		LogUtil.Init.attach(0);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	public static void main(String[] args){
		TestTemplate.run(Renderer.GUI::new, (fil, snapshot)->{
			fil.getFile("buffer").writeAll("ay lamaooo".getBytes());
//			fil.getFile("buffer").delete();
		});
	}
	
}
