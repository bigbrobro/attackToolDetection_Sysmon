package logparse;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Detect mimikatz comparing Common DLL List with exported Sysmon event log.
 * Output processes that load all DLLs in Common DLL List and detection rate.
 * 
 * @version 1.0
 * @author Mariko Fujimoto
 */
public class SysmonDetecter {

	 /**
	 * Specify file name of mimikatz
	 */
	//private static final String ATTACK_MODULE_NAME = "mimikatz.exe";
	//private static final String ATTACK_MODULE_NAME = "powershell.exe";
	//private static final String ATTACK_MODULE_NAME = "caidao.exe";
	//private static final String ATTACK_MODULE_NAME = "wce.exe";
	//private static final String ATTACK_MODULE_NAME = "pwdump";
	private static final String ATTACK_MODULE_NAME = "htran.exe";
	private static final String MIMI_MODULE_NAME = "mimikatz.exe";
	private static Map<String, HashSet> log;
	private static Map<Integer, HashSet> image;
	private static HashSet<String> commonDLLlist = new HashSet<String>();
	private static String commonDLLlistFileName = null;
	private static String outputDirName = null;
	private static int TruePositiveCnt = 0;
	private static int falsePositiveCnt = 0;
	private static int falseNegativeCnt = 0;
	
	private int totalProcessCnt=0;
	private int processCntMimi=0;
	private int detectedProcessCntMimi=0;
	private int dllCnt=0;
	private void readCSV(String filename) {

		try {
			File f = new File(filename);
			BufferedReader br = new BufferedReader(new FileReader(f));
			String line;
			int processId = 0;
			String date="";
			String image="";
			String imageLoaded ="";
			
			while ((line = br.readLine()) != null) {
				String[] data = line.split(",", 0);
				for (String elem : data) {
					if (elem.startsWith("情報") || elem.startsWith("Information,7")) {
						  date = data[1];
					} else if (elem.startsWith("ProcessId:")) {
						processId = Integer.parseInt(parseElement(elem,": "));
					} else if (elem.startsWith("Image:")|| elem.endsWith(".exe")) {
						image=parseElement(elem,": ");
						image=image.toLowerCase();
					}
					if(image.endsWith(MIMI_MODULE_NAME)) {
						continue;
					}
					if (elem.startsWith("ImageLoaded:") && elem.endsWith(".dll") || elem.endsWith(".dll")) {
						imageLoaded = parseElement(elem,": ");
						HashSet<EventLogData> evSet;
						if (null == log.get(processId+image)) {
							evSet=new HashSet<EventLogData>();
						} else {
							evSet = log.get(processId+image);
						}
						imageLoaded=imageLoaded.toLowerCase();
						evSet.add(new EventLogData(date,imageLoaded,image));
						log.put(processId+image, evSet);
					}

				}
			}
			br.close();

		} catch (IOException e) {
			System.out.println(e);
		}

	}

	private String parseElement(String elem, String delimiter) {
		String value="";
		try{
		String elems[] = elem.split(delimiter);
		value = elems[elems.length-1].trim();
		}catch (RuntimeException e){
			e.printStackTrace();
		}
		return value;
	}

	private void outputLoadedDLLs(Map map, String outputFileName) {
		File file = new File(outputFileName);
		String filename=file.getName();
		FileWriter filewriter = null;
		BufferedWriter bw = null;
		PrintWriter pw = null;
		try {
			filewriter = new FileWriter(file);
			bw = new BufferedWriter(filewriter);
			pw = new PrintWriter(bw);
			for (Iterator it = map.entrySet().iterator(); it.hasNext();) {
				Map.Entry<Integer, HashSet> entry = (Map.Entry<Integer, HashSet>) it.next();
				Object processId = entry.getKey();
				HashSet<EventLogData> evS = (HashSet<EventLogData>) entry.getValue();
				dllCnt+=evS.size();
				//HashSet<String> imageLoadedList = new HashSet<String>();
				TreeSet<String> imageLoadedList = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
				for (EventLogData ev: evS) {
					String image=ev.getImage();
					if (image.endsWith(MIMI_MODULE_NAME)) {
						break;
					}
					String[] dlls=ev.getImageLoaded().split("\\\\");
					String dllName=dlls[dlls.length-1];
					dllName=dllName.toLowerCase();
					imageLoadedList.add(dllName);
				}
				boolean result = isMatchWithCommonDLLlist(commonDLLlistFileName, imageLoadedList);
				for (EventLogData ev: evS) {
						pw.println(processId + "," +ev.getImageLoaded() + ", " + ev.getImage() + ", " +ev.getDate()+", " +result);
				}
				boolean containsMimikatz = false;
				HashSet<EventLogData> evSet = log.get(processId);
				HashSet<String> imageList=new HashSet<String>();
				for (EventLogData ev : evSet) {
					String image=ev.getImage();
					if (image.contains(ATTACK_MODULE_NAME)) {
						// mimikatz is executed
						containsMimikatz = true;
						imageList.add(image);
						processCntMimi++;
						break;
					} 
				}
				// Matched with Common DLL List 
				if (result) {
					System.out.println("Detected. filename:"+filename+", Process ID:"+processId);
					detectedProcessCntMimi++;
					if (!containsMimikatz) {
						// mimikatz is not executed
						falsePositiveCnt++;
						System.out.println("FP occurs. filename:"+filename+", Process ID:"+processId);
					} else {
						TruePositiveCnt++;
					}
				} else {
					// Do not matched with Common DLL List 
					if (containsMimikatz) {
						// mimikatz is executed
						/*
						boolean mimiProcessExists=false;
						for(String image : imageList){
							if(image.endsWith(ATTACK_MODULE_NAME)){
								mimiProcessExists=true;
								break;
							}
						}
						*/
						//if(!mimiProcessExists){
						falseNegativeCnt++;
						System.out.println("FN occurs. filename:"+filename+", Process ID:"+processId);
						//}
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			pw.close();
			try {
				bw.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/*
	 * private boolean isMatchWithCommonDLLlist(String
	 * commonDLLlistFileName,TreeSet<String> imageLoadedList) { boolean result =
	 * imageLoadedList.containsAll(commonDLLlist); return result; }
	 */
	
	private boolean isMatchWithCommonDLLlist(String commonDLLlistFileName,TreeSet<String> imageLoadedList) {
		for(String dll:commonDLLlist) {
			if(!imageLoadedList.contains(dll)) {
				return false;
			}
		}
		return true;
	}

	/**
	* Parse CSV files exported from Sysmon event log.
	* Output process/loaded DLLs and detect which matches Common DLL List.
	* @param inputDirname 
	*/
	public void outputLoadedDlls(String inputDirname) {
		File dir = new File(inputDirname);
		File[] files = dir.listFiles();

		for (File file : files) {
			String filename = file.getName();
			if (filename.endsWith(".csv")) {
				readCSV(file.getAbsolutePath());
				outputLoadedDLLs(log, this.outputDirName + "/" + filename);
				totalProcessCnt=totalProcessCnt+=log.size();
				log.clear();
			} else {
				continue;
			}
		}

	}

	/**
	* Evaluate detection rate using Common DLL List.
	*/
	public void outputDetectionRate() {
		FileWriter filewriter = null;
		BufferedWriter bw = null;
		PrintWriter pw = null;

		// mimikatz以外のプロセス数
		int normalProcessCnt =totalProcessCnt-this.processCntMimi;
		
		// mimikatz以外と判定したプロセスの割合
		double trueNegativeRate = (double) (totalProcessCnt-this.detectedProcessCntMimi) / (double)normalProcessCnt;
		// 正しくmimikatzと判定したプロセスの割合
		double truePositiveRate = (double) TruePositiveCnt / (double)processCntMimi;
		
		// mimikatz以外のプロセスをmimikatzと検知した割合
		double falsePositiveRate = (double) falsePositiveCnt / totalProcessCnt;
		double falseNegativeRate = (double) falseNegativeCnt / this.processCntMimi;
		
		String truePositiveRateS = String.format("%.2f", truePositiveRate);
		String trueNegativeRateS = String.format("%.2f", trueNegativeRate);
		String falsePositiveRateS = String.format("%.2f", falsePositiveRate);
		String falseNegativeRateS = String.format("%.2f", falseNegativeRate);
		try {
			filewriter = new FileWriter(this.outputDirName + "/" + "detectionRate.txt");
			bw = new BufferedWriter(filewriter);
			pw = new PrintWriter(bw);
			pw.println("Total process count: " + totalProcessCnt);
			pw.println("True Positive count: " + TruePositiveCnt + ", True Positive rate: " + truePositiveRateS);
			pw.println("True Negative count: " + (totalProcessCnt-this.detectedProcessCntMimi-falseNegativeCnt) + ", True Negative rate: " + trueNegativeRateS);
			pw.println("False Positive count: " + falsePositiveCnt + ", False Positive rate: " + falsePositiveRateS);
			pw.println("False Negative count: " + falseNegativeCnt + ", False Negative rate: " + falseNegativeRateS);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			pw.close();
			try {
				bw.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		System.out.println("Total process count: " + totalProcessCnt);
		System.out.println("True Positive count: " + TruePositiveCnt + ", True Positive rate: " + truePositiveRateS);
		System.out.println("True Negative count: " + (totalProcessCnt-this.detectedProcessCntMimi-falseNegativeCnt) + ", True Negative rate: " + trueNegativeRateS);
		System.out.println("False Positive count: " + falsePositiveCnt + ", False Positive rate: " + falsePositiveRateS);
		System.out.println("False Negative count: " + falseNegativeCnt + ", False Negative rate: " + falseNegativeRateS);
		double average=dllCnt/totalProcessCnt;
		System.out.println("average of DLls: "+average);
	}

	private void readCommonDLLList() {
		BufferedReader br = null;
		try {
			File f = new File(commonDLLlistFileName);
			br = new BufferedReader(new FileReader(f));
			String line;
			while ((line = br.readLine()) != null) {
				String dll = line.trim();
				commonDLLlist.add(dll);
			}
		} catch (IOException e) {
			System.out.println(e);
		} finally {
			try {
				br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void detelePrevFiles(String outDirname) {
		Path path = Paths.get(outDirname);
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(path, "*.*")) {
			for (Path deleteFilePath : ds) {
				Files.delete(deleteFilePath);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static void printUseage() {
		System.out.println("Useage");
		System.out.println("{iputdirpath} {Common DLL List path} {outputdirpath} (-dr)");
		System.out.println("If you evaluate detection rate using Common DLL Lists specify -dr option.)");
	}

	public static void main(String args[]) {
		SysmonDetecter sysmonParser = new SysmonDetecter();
		String inputdirname="" ;
		if (args.length < 3) {
			printUseage();
		} else if (args.length > 0) {
			inputdirname = args[0];
		}
		if (args.length > 1) {
			commonDLLlistFileName = args[1];
		}
		if (args.length > 2) {
			outputDirName = args[2];
		}
		log = new HashMap<String, HashSet>();
		image = new HashMap<Integer, HashSet>();
		sysmonParser.detelePrevFiles(outputDirName);
		sysmonParser.readCommonDLLList();
		sysmonParser.outputLoadedDlls(inputdirname);
		if (args.length > 3) {
			String option = args[3];
			if (option.equals("-dr")) {
				sysmonParser.outputDetectionRate();
			}
		}

	}

}
