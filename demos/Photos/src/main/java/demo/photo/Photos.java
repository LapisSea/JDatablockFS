package demo.photo;

import com.lapissea.util.LogUtil;
import demo.photo.ui.Home;

import javax.swing.*;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_CALL_THREAD;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

public final class Photos{
	
	static{
		LogUtil.Init.attach(USE_CALL_THREAD|USE_CALL_POS|USE_TABULATED_HEADER);
		try{
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		}catch(Exception ignored){ }
		
	}
	
	public static void main(String[] args){
		new Home().start();
	}
	
}
