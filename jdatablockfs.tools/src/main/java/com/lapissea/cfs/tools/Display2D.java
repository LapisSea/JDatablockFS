package com.lapissea.cfs.tools;


import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.render.G2DBackend;
import com.lapissea.util.MathUtil;
import com.lapissea.util.UtilL;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.Optional;

import static javax.swing.SwingUtilities.invokeLater;

@SuppressWarnings("AutoBoxing")
public class Display2D implements DataLogger{
	
	private final Frame frame=new Frame();
	private final Panel pan;
	
	private final SessionHost sessionHost=new SessionHost();
	
	private final G2DBackend         renderer;
	private final BinaryGridRenderer drawing;
	
	public Display2D(){
		File f=new File("wind");
		
		var t=new Thread(()->{
			while(true){
				UtilL.sleep(1);
				watchLoop();
			}
		});
		t.setDaemon(true);
		t.start();
		
		try(var in=new BufferedReader(new FileReader(f))){
			frame.setLocation(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
			frame.setSize(Integer.parseInt(in.readLine()), Integer.parseInt(in.readLine()));
		}catch(Throwable ignored){
			frame.setSize(800, 800);
			frame.setLocationRelativeTo(null);
		}
		
		pan=new Panel(){
			@Override
			public void update(Graphics g){
				paint((Graphics2D)g);
			}
			@Override
			public void paint(Graphics g){
				paint((Graphics2D)g);
			}
			
			public void paint(Graphics2D g){
				try{
					drawing.render();
				}catch(Throwable e){
					new RuntimeException("Failed to complete frame render", e).printStackTrace();
				}
				g.drawImage(renderer.getRender(), 0, 0, null);
			}
		};
		renderer=new G2DBackend(pan);
		drawing=new BinaryGridRenderer(renderer);
		
		frame.setLayout(new BorderLayout());
		frame.add(pan);
		
		frame.addWindowListener(
			new WindowAdapter(){
				@Override
				public void windowClosed(WindowEvent e){System.exit(0);}
			}
		);
		
		frame.addKeyListener(new KeyAdapter(){
			@Override
			public void keyPressed(KeyEvent e){
				sessionHost.cleanUpSessions();
				drawing.displayedSession.ifPresent(ses->{
					if(e.getKeyChar()=='a'||e.getKeyCode()==37) ses.framePos.set(ses.framePos.get()-1);
					if(e.getKeyChar()=='d'||e.getKeyCode()==39) ses.framePos.set(ses.framePos.get()+1);
					ses.framePos.set(MathUtil.snap(ses.framePos.get(), 0, ses.frames.size()-1));
					renderer.markFrameDirty();
					
					if(e.getKeyCode()==122){
						frame.dispose();
						if(frame.isUndecorated()){
							frame.setUndecorated(false);
							frame.setExtendedState(JFrame.NORMAL);
							frame.setSize(800, 800);
							frame.setVisible(true);
							
						}else{
							frame.setUndecorated(true);
							frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
							frame.setVisible(true);
						}
					}
				});
			}
		});
		
		frame.addComponentListener(new ComponentAdapter(){
			@Override
			public void componentResized(ComponentEvent e){
				if(drawing.displayedSession.isPresent()){
					drawing.calcSize(drawing.getFrame(drawing.getFramePos()).data().data().length, true);
				}
				renderer.markFrameDirty();
			}
			
			@Override
			public void componentMoved(ComponentEvent e){
				super.componentMoved(e);
				if(!frame.isUndecorated()){
					try(var in=new BufferedWriter(new FileWriter(f))){
						in.write(Integer.toString(frame.getLocation().x));
						in.newLine();
						in.write(Integer.toString(frame.getLocation().y));
						in.newLine();
						in.write(Integer.toString(frame.getSize().width));
						in.newLine();
						in.write(Integer.toString(frame.getSize().height));
						in.newLine();
					}catch(Throwable ignored){}
				}
			}
		});
		
		pan.addMouseMotionListener(new MouseMotionAdapter(){
			@Override
			public void mouseMoved(MouseEvent e){
				renderer.markFrameDirty();
			}
			
			@Override
			public void mouseDragged(MouseEvent e){
				int width=pan.getWidth();
				int x    =MathUtil.snap(e.getX(), 0, width);
				
				float val=x/(float)width;
				
				sessionHost.cleanUpSessions();
				drawing.displayedSession.ifPresent(ses->ses.framePos.set((int)(val*(ses.frames.size()-1))));
				
				renderer.markFrameDirty();
			}
		});
		
		pan.addMouseListener(new MouseAdapter(){
			@Override
			public void mouseClicked(MouseEvent e){
				sessionHost.cleanUpSessions();
				drawing.displayedSession.ifPresent(ses->{
					if(ses.frames.isEmpty()) return;
					ses.frames.get(ses.framePos.get()).data().printStackTrace();
					renderer.markFrameDirty();
				});
			}
		});
		
		frame.setVisible(true);
//		frame.createBufferStrategy(2);
	}
	private void watchLoop(){
		sessionHost.cleanUpSessions();
		
		var activeSession=sessionHost.activeSession.get();
		if(drawing.displayedSession!=activeSession){
			drawing.displayedSession=activeSession;
			renderer.markFrameDirty();
		}
		
		if(drawing.displayedSession.isEmpty()||drawing.displayedSession.get().frames.isEmpty()) return;
		
		if(renderer.notifyDirtyFrame()){
			invokeLater(pan::repaint);
		}
	}
	
	@Override
	public DataLogger.Session getSession(String name){
		renderer.markFrameDirty();
		return sessionHost.getSession(name);
	}
	
	@Override
	public void destroy(){
		invokeLater(()->{
			frame.setVisible(false);
			frame.dispose();
		});
		sessionHost.destroy();
		drawing.displayedSession=Optional.empty();
	}
}
