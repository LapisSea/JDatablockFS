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
//			FSFTest_console.doCommand(fil, snapshot, "for i 0 100 write file%i loool that's funny");
			
			FSFTest_console.doCommand(fil, snapshot, "for i 0 30 write file%i haha");
			FSFTest_console.doCommand(fil, snapshot, "for i 0 30 write file%i lol haha");
		});
	}
}
