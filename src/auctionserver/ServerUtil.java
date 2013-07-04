/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package auctionserver;

import java.awt.*;
import java.text.*;
import java.util.*;
import javax.swing.text.*;

/**
 *
 * @author kogayou
 */
public class ServerUtil {
    
    public static String getTime()
    {
        DateFormat sdf=new SimpleDateFormat("HH:mm:ss");
        return "["+sdf.format(new Date())+"] ";
    }
    
    public static SimpleAttributeSet setAttributeSet(Color color) {
        SimpleAttributeSet attrset=new SimpleAttributeSet();
        StyleConstants.setForeground(attrset,color); 
        return attrset;
    }
}
