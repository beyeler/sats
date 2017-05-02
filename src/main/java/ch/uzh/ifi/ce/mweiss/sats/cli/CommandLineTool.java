package ch.uzh.ifi.ce.mweiss.sats.cli;

import java.io.File;
import java.io.IOException;

import ch.uzh.ifi.ce.mweiss.sats.core.api.IllegalConfigException;
import ch.uzh.ifi.ce.mweiss.sats.core.api.PathResult;
import ch.uzh.ifi.ce.mweiss.sats.core.model.UnsupportedBiddingLanguageException;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;


/**
 * Access class for the Command Line Interface
 *
 */
public class CommandLineTool {
    
    public static String KEY_HELP = "help";
    
    public static void main( String[] args ) throws IOException{
                               
        // Phase 1
        // Check for help request without provided model
        OptionParser helpFirstParser = new OptionParser();
        helpFirstParser.accepts("help");
        helpFirstParser.accepts("model").withRequiredArg().ofType(Model.class);
        helpFirstParser.allowsUnrecognizedOptions();
        OptionSet helpFirstOptions = helpFirstParser.parse(args);
        if(helpFirstOptions.has("help") && !helpFirstOptions.has("model")){
            ModelOptionParser.printGeneralHelp();
            return;
        }
        
        // Phase 2
        // Read Model
        OptionParser modelDiscovery = new OptionParser();
        modelDiscovery.accepts("model").withRequiredArg().ofType(Model.class).required();
        modelDiscovery.allowsUnrecognizedOptions();
        Model model;
        try{            
            OptionSet modelOption = modelDiscovery.parse(args);
            model = (Model) modelOption.valueOf("model");
        }catch (OptionException e) {
            e.printStackTrace();
            ModelOptionParser.printGeneralHelp();
            return;
        }
        
        // Phase 3
        // Init Model Parsing
        ModelOptionParser modelParser = ModelOptionParser.createOptionParser(model);
        try {
            PathResult pathResult = modelParser.treatResult(args);
            printSuccess(args, pathResult);
        } catch (IllegalConfigException e) {
            e.printStackTrace();
        } catch (UnsupportedBiddingLanguageException e) {
            String message =  e.getMessage();
            System.out.print("The chosen bidding language (iterator and/or XOR/XOR-Q) cannot be used for the chosen model    ");
            
            if(message != null){
                System.out.println(message);
            }else{
                System.out.println();
            }
            e.printStackTrace();
        }
   
        
    }
    
    private static void printSuccess(String[] args, PathResult pathResult){
        OptionParser checkMuted = new OptionParser();
        checkMuted.accepts(ModelOptionParser.KEY_MUTE);
        checkMuted.allowsUnrecognizedOptions();
        if(!checkMuted.parse(args).has(ModelOptionParser.KEY_MUTE)){
            System.out.println("Successfully created value files:");
            System.out.println("Bids can be found in the following files");
            for(File file : pathResult.getValueFiles()){
                System.out.println("\t - " + file.getPath());
            }
            if(pathResult.isAreInstancesSerialized()){
                System.out.println("The serialized instances can be found here:");
                System.out.println("\t - " + pathResult.getInstanceFolder().getPath());
            }
        }
        
    }

    public static void printHelpIfRequested(OptionSet generalOptions, Model model, OptionParser toPrint){     
        if(generalOptions.has("help")){
           printHelp(String.valueOf(model), toPrint);
        }    
    }
    
    public static void printHelp(String modelName, OptionParser toPrint){
        System.out.println();
        System.out.println("====Options applicable for " + modelName + "====");
        try {
            toPrint.printHelpOn(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
 
}
