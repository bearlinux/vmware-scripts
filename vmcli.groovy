#!/bin/env groovy
package com.petsmart.ssg.unix
@Grab(group='com.vmware', module='vijava', version='5.1')
@Grab(group='com.owteam.engUtils', module='netrc', version='2.0.1')
@Grab(group='com.owteam.engUtils', module='log', version='2.0.0')
@Grab('com.owteam.engUtils:cert:2.0.0' )
@Grab(group="ch.ethz.ganymed", module="ganymed-ssh2", version="build210")
@Grab(group='commons-net', module='commons-net', version='3.3')


import ch.ethz.ssh2.*;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import com.owteam.engUtils.cert.CertTool;
import com.owteam.engUtils.netrc.Netrc;
import com.owteam.engUtils.log.EngLog;
import com.vmware.vim25.*
import com.vmware.vim25.mo.*
import com.vmware.vim25.mo.util.*;
import groovy.json.*
import groovy.util.logging.*;
import groovy.util.ConfigObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL
import java.net.Inet4Address;
import java.net.MalformedURLException;
import java.rmi.RemoteException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.Properties;
import org.apache.commons.net.util.*;
import org.slf4j.LoggerFactory;


@Slf4j
public class vmcli{

	private static File rsaKey = new File(System.getenv("HOME")+File.separator+".ssh"+File.separator+"id_rsa");
	private static File dsaKey = new File(System.getenv("HOME")+File.separator+".ssh"+File.separator+"id_dsa");

	static final Pattern diskReplaceSnapRegex = ~/snap.*/;
	static final Pattern diskReplaceVmRegex = ~/.*orac.*/;
	static final Pattern templateRegex = ~/.*template.*/;

	public static void main(String[] args){

		def serverName;
		def vm;
		def datastore;
		EngLog.clearAppenders();
		EngLog.addConsoleAppender();
                //EngLog.addFileAppender();
		Logger root = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		root.setLevel(Level.INFO);

		def cli = new CliBuilder(usage:'resnap-dev [options] ');
		cli.h(longOpt:'help', 'print usage statement');
		cli.l(longOpt:'listsnaps', 'list datastores with snap in the name');
		cli.a(longOpt:'add-datastore-vmdks', 'add the vmdks for the selected datastore to the selected vm');
		cli.r(longOpt:'remove-datastore-vmdks', 'detach the vmdks for the the selected datastore from the selected vm');
		cli.i(longOpt:'interactive', 'ask for each option interactively');
		cli.s(longOpt:'vcenterServer', args:1, argName:'vcenterServer', required:true, 'The vcenter server to connect to');
		cli.d(longOpt:'datastore', args:1, argName:'datastore', 'The snapshot datastore containing only instance specific disks');
		cli.D(longOpt:'debug', 'Enable debug level output');
		cli.v(longOpt:'vm', args:1, argName:'vm', 'The vm to change disk mapping for');

		def options = cli.parse(args);
		if(!options){log.error("option parse error");System.exit(1)}
		if(options.debug){
                	root.setLevel(Level.DEBUG);
		}
		
		serverName=options.vcenterServer;
		def creds = Netrc.getInstance().getCredentials(serverName);
		if(creds == null){
			log.error("Please add creds for $serverName to your .netrc");
			System.exit(1);
		}
		def si = new ServiceInstance(new URL("https://$serverName/sdk"), creds.getUserName(), creds.getPassword(), true);
		def nav = new InventoryNavigator(si.rootFolder);
		
		if(!(options.listsnaps||options.add||options.remove||options.interactive)){
			cli.usage();
			log.error("Please choose one of listsnaps,add,remove,interactive");
			System.exit(1);
		}
		if(options.help){
			cli.usage();
			System.exit(0);
		}

		if(options.listsnaps){
			def dsList = nav.searchManagedEntities("Datastore").grep{return it.name.matches(diskReplaceSnapRegex);}
			dsList.eachWithIndex{ it, i ->
				println("${it.name}");
			}
			System.exit(0);
		}
		if(!options.interactive&&!options.vm&&!options.datastore){
			cli.usage();
			log.error("Please specify vm and datastore or interactive");
			System.exit(1);
		}
		if(options.vm){
			vm = nav.searchManagedEntity("VirtualMachine",options.vm);
		}
		if(options.datastore){
			datastore= nav.searchManagedEntity("Datastore",options.datastore);
		}
		if(options.interactive){
			talkToUser(si,nav);
		}else if(options.add){
			addDisksAndReconfigure(vm,datastore);
		}else if(options.remove){
			removeDisksAndReconfigure(vm,datastore,si);
		}else{
			cli.usage();
			System.exit(1);
		}


		si.getServerConnection().logout()


	}

	public static void talkToUser(ServiceInstance si, InventoryNavigator nav){
		def dsList = nav.searchManagedEntities("Datastore").grep{return it.name.matches(diskReplaceSnapRegex);}
		def br = new BufferedReader(new InputStreamReader(System.in));
		println("Select snapshot you would like to add disks for or press Crtl-C to cancel");
		dsList.eachWithIndex{ it, i ->
			println("$i)	${it.name}");
		}
		def response = br.readLine();
		while(!response.matches("[0-9]+")){
			println("That's not a number, try again");
			br.readLine();
		}
		response = Integer.parseInt(response); 
		def datastore = dsList[response];
		println("Select the VM to change(list is filtered to systems with orac in the name.");
		def vmList = nav.searchManagedEntities("VirtualMachine").grep{return it.name.matches(diskReplaceVmRegex);}
		vmList.eachWithIndex{ it, i ->
			println("$i)	${it.name}");
		}
		response = br.readLine();
		while(!response.matches("[0-9]+")){
			println("That's not a number, try again");
			br.readLine();
		}
		response = Integer.parseInt(response); 
		def vm = vmList[response];
		println("Would you like to 0) add or 1) remove the datastore disks from the vm");
		response = br.readLine();
		while(!response.matches("[01]")){
			println("That's not 0 or 1, try again");
			br.readLine();
		}
		response = Integer.parseInt(response); 
		if(response == 0){
			 addDisksAndReconfigure(vm,datastore);
		}else if(response == 1){
			removeDisksAndReconfigure(vm,datastore,si);
		}

	}

	public static OptionValue[] createMutliWriterOptions(){
		def options=[];
		for(int i=0;i<5;i++){
			for(int j=0;j<16;j++){
				if(i==0&&j<2) continue;
				if(j==7) continue;
				def option=new OptionValue();
				option.key="scsi${i}:${j}.sharing".toString();
				option.value="multi-writer";
				options.add(option);
			}
		}
		return options.toArray();
	}
	public static List getPaths(Datastore datastore){
		ArrayList paths = new ArrayList();
		log.debug("Datastore contains the following non-template vmdk files");
		def hs = new HostDatastoreBrowserSearchSpec();
		def dsPath = "[${datastore.name}]";
		hs.query= new VmDiskFileQuery();
        	Task task = datastore.getBrowser().searchDatastoreSubFolders_Task(dsPath,hs);
        	if (task.waitForTask() == Task.SUCCESS) {
				def searchResults = task.getTaskInfo().getResult().getHostDatastoreBrowserSearchResults();
				searchResults.each{ searchResult ->
               		FileInfo[] fis = searchResult.getFile();
					fis.grep{return !it.getPath().matches(templateRegex)}.each{
						log.debug("Path: ${searchResult.folderPath}${it.path}");
						paths.add("${searchResult.folderPath}${it.path}");
					}
				}
       		}else{
			def taskInfo=task.getTaskInfo();
			def state=taskInfo.getState();
			def message=taskInfo.error.getLocalizedMessage();
			log.error("Not success: $state , message: $message");
		}
		return paths;


	}

	public static int getNextDeviceKey(VirtualMachine vm,Set extraAllocatedKeys){
		def usedDevIds = vm.config.hardware.device.collect{return it.key}.grep{return it>2000&&it<3000;}+extraAllocatedKeys;
		usedDevIds = usedDevIds.sort();
		def nextid=	usedDevIds.last()+1;
		extraAllocatedKeys.add(nextid);
		return nextid;
	}
	
	public static DevicePair getControllerAndUnit(VirtualMachine vm,Set usedPairs){
		def controllerKeys = vm.config.hardware.device.grep{ return it instanceof ParaVirtualSCSIController}.collect{return it.key};
		def diskPairs=vm.config.hardware.device.grep{ return it instanceof VirtualDisk}.collect{ 
			def result = new DevicePair();
			result.controllerKey=it.controllerKey
			result.unitNumber=it.unitNumber
			return result;
		};
		diskPairs=diskPairs+usedPairs;
		log.trace("DiskPairs: $diskPairs");
		log.trace("controller keys: $controllerKeys");
		def deviceId =new DevicePair();
		Iterator i = controllerKeys.iterator();
		while(i.hasNext()){
			def controllerKey=i.next()
			for(int j=0;j<15;j++){
				if(j==7){
					continue;
				}
				deviceId.controllerKey=controllerKey;
				deviceId.unitNumber=j;
				log.trace("device: $deviceId found in ${diskPairs} : ${diskPairs.contains(deviceId)}");
				if(!diskPairs.contains(deviceId)){
					usedPairs.add(deviceId);
					return deviceId;
				}else{
					log.trace("device: $deviceId found in ${diskPairs}");
				}
			}
		}
				
		log.error("no free devices found!");
	}

	public static void vmreconfigure(VirtualMachine vm, VirtualMachineConfigSpec vmConfigSpec){
		
		def task = vm.reconfigVM_Task(vmConfigSpec);

		if (task.waitForTask() == Task.SUCCESS) {
			log.info("reconfigure successful!");	
		}else{
			def taskInfo=task.getTaskInfo();
			def state=taskInfo.getState();
			def message=taskInfo.error.getLocalizedMessage();
			log.error("Not success: $state , message: $message");
		}


	}
	
	public static void addDisksAndReconfigure(VirtualMachine vm, Datastore ds){
		def diskPaths = getPaths(ds)
		
		//log.info("Controller keys: $controllerKeys");
		def diskSpecs = [];
		def usedKeys= new HashSet(); // a list of deviceKeys
		def usedPairs= new HashSet(); // A list of ControllerKey,unitNumber pairs
		//diskPaths.each{ diskPath ->
		diskPaths.each{ diskPath ->
			String diskName=diskPath.replaceAll(".*/","").replaceAll(".vmdk","");
			VirtualDiskFlatVer2BackingInfo diskfileBacking = new VirtualDiskFlatVer2BackingInfo();
			diskfileBacking.setFileName(diskPath);
			diskfileBacking.setDiskMode("independent_persistent");
	
			Description desc = new Description();
			desc.setLabel(diskPath);
			desc.setSummary(diskPath);

			def devPair = getControllerAndUnit(vm,usedPairs);	
			VirtualDisk disk = new VirtualDisk();
			disk.setBacking(diskfileBacking);
			disk.setKey(getNextDeviceKey(vm,usedKeys));
			disk.setDeviceInfo(desc);
			disk.setControllerKey(devPair.controllerKey);
			disk.setUnitNumber(devPair.unitNumber);

			def diskSpec = new VirtualDeviceConfigSpec();
			diskSpec.setOperation(VirtualDeviceConfigSpecOperation.add);
			diskSpec.setDevice(disk);
			
			diskSpecs.add(diskSpec);
		}

		//Unset options remain the same :)
		VirtualMachineConfigSpec vmConfigSpec = new VirtualMachineConfigSpec();
		vmConfigSpec.setDeviceChange((VirtualDeviceConfigSpec[])diskSpecs.toArray());
		vmConfigSpec.extraConfig=createMutliWriterOptions();
		//println(vmConfigSpec);
		def json=JsonOutput.toJson(vmConfigSpec);
		log.debug(JsonOutput.prettyPrint(json))
		vmreconfigure(vm,vmConfigSpec);
		
	}
	
	public static void removeDisksAndReconfigure(VirtualMachine vm, Datastore ds,ServiceInstance si){
		
		//log.info("Controller keys: $controllerKeys");
		def diskSpecs = [];
		def usedKeys= new HashSet(); // a list of deviceKeys
		def usedPairs= new HashSet(); // A list of ControllerKey,unitNumber pairs
		//diskPaths.each{ diskPath ->
		def disks=vm.config.hardware.device.grep{ return it instanceof VirtualDisk}
		log.debug("disks on vm:  $disks");
		
		disks.grep{def ds1= new Datastore(si.getServerConnection(),it.backing.datastore);return ds1.name == ds.name}.each{disk ->
			log.debug("Disk ${disk.deviceInfo.label} will be detached");
		
			def diskSpec = new VirtualDeviceConfigSpec();
			diskSpec.setOperation(VirtualDeviceConfigSpecOperation.remove);
			diskSpec.setDevice(disk);
			diskSpecs.add(diskSpec);
		}
		//Unset options remain the same :)
		VirtualMachineConfigSpec vmConfigSpec = new VirtualMachineConfigSpec();
		vmConfigSpec.setDeviceChange((VirtualDeviceConfigSpec[])diskSpecs.toArray());
		vmConfigSpec.extraConfig=createMutliWriterOptions();
		//println(vmConfigSpec);
		def json=JsonOutput.toJson(vmConfigSpec);
		log.debug(JsonOutput.prettyPrint(json))
		vmreconfigure(vm,vmConfigSpec);
		
	}

	public static void printGuestAndFullName(ServiceInstance si){
		def mes = new InventoryNavigator(si.rootFolder).searchManagedEntities("VirtualMachine")
		mes.each {
    			println "${it.getName} : " + it.getConfig().getGuestFullName()
		}
	}


        public String rexec(String User,String host,String cmd){
	       def conn = new Connection(host);
                conn.connect();
                Boolean connected = false;
                connected = conn.authenticateWithPublicKey(user,rsaKey,null);
                if(!connected ){
                        log.debug("rsaKey (${rsakey.path}) didn't work trying dsa");
                        connected = conn.authenticateWithPublicKey(user,dsaKey,null);
                }
                //Boolean connected = conn.authenticateWithPassword(user,pass);
                if(!connected ){
                        log.error("login failed to $host");
                        //throw new SshAuthenticationException("login failed for host: $host");
                }

                Session sess = conn.openSession();

                def out = new InputStreamReader(new StreamGobbler(sess.getStdout()));
                def err = new InputStreamReader(new StreamGobbler(sess.getStderr()));
                sess.execCommand(cmd)
                String errText=err.text;
                String result=out.text;
                if(errText.length()>1){log.warn("${conn.getHostname()}: ${errText}")}
                if(sess.getExitStatus()>0){
                        log.error("exit code ${sess.getExitStatus()} for command $cmd with output $result");
                        throw new Exception("exit code ${sess.getExitStatus()} for command $cmd with output $result");
                }else{
                        log.debug("exit code ${sess.getExitStatus()} for command $cmd with output ---- $result ----");
                }
                sess.close();
                return result;

	}

	public void powerOnVM(VirtualMachine vm){
		Task powerOnTask = vm.powerOnVM_Task();

		while (powerOnTask.getTaskInfo().getState().equals(TaskInfoState.running)) {
			Thread.sleep(2000);
			log.info("Percentage complete: " + powerOnTask.getTaskInfo().progress);
		}
		if(!powerOnTask.getTaskInfo().getState().equals(TaskInfoState.success)) {
			throw Exception("Failed to power on server vm.name");
		}
		String PowerOnCompletionTime = (new SimpleDateFormat()).format(powerOnTask.getTaskInfo().completeTime.getTime());
		log.info("PowerOn finished at " + PowerOnCompletionTime + " with state " + powerOnTask.getTaskInfo().state.toString());
	}

	public void setHotAddAndPatch(ServiceInstance si,String vmName){
		def vm = new InventoryNavigator(si.rootFolder).searchManagedEntity("VirtualMachine",vmName)
		if(vm==null){
			throw new Exception("VM $vmName not found in vcenter $servername!");
		}
				
   	 	def props=vm.getConfig().extraConfig;
    		def propMap = [:];
    		props.each{propMap.put(it.key,it.value)};
		
    		//println("${vm.getName()} ${cpuHot} ${memHot} ${vm.getConfig().getGuestFullName()}")
    		//println("${vm.getName()} ${propMap["vcpu.hotadd"]} ${propMap["mem.hotadd"]}")

 		Task task = vm.createSnapshot_Task(snapshotname, "auto", false, false);
		if(task.waitForMe()==Task.SUCCESS){
			System.out.println("Successfully created snapshot " + snapshotname + " on " + vmName);
		}else{
			throw new Exception("Error creating snapshot!");
		} 
	
		//rexec(vmName,"wget patchserver/patchcommand");
		//rexec(vmName,"bash patchcommand");
		rexec(vmName,"yum -y update");

		//schedule compatibility upgrade on reboot
		rexec(vmName,"shutdown -h now");


		if(propMap["vcpu.hotadd"]==null || propMap["mem.hotadd"]==null){
			System.out.println("Will need to update vm for hotadd options");
			VirtualMachineConfigSpec vms = new VirtualMachineConfigSpec();
			extraConfigList = new ArrayList();
			extraConfigList += vms.extraConfig;
			if(propMap["vcpu.hotadd"]==null){extraConfigList << new OptionValue("vcpu.hotadd",true)}
			if(propMap["mem.hotadd"]==null){extraConfigList << new OptionValue("mem.hotadd",true)}
			vms.extraConfig = extraConfigList.toArray();
			vm.reconfigVM_Task(vms);
		}

		powerOnVM(vm)

		rexec(vmName,"wget patchserver/update-vmware-tools-script");
		rexec(vmName,"bash update-vmware-tools-script");
	}


	static void printConsoleURL(ServiceInstance si, String vcenterHost, String vmName){
		int htmlPort = 7331;
		int port = 443;

        	ManagedEntity vmm = new InventoryNavigator(si.rootFolder).searchManagedEntity("VirtualMachine",vmName);
		if(vmm==null){
			log.error("Virtual Machine ${vmName} not found, exiting");
		}
		SessionManager sm = si.getSessionManager();
		String sessionTicket = sm.acquireCloneTicket();
		String vmId = vmm.getMOR().getVal();

		// Yes I wrote a utility class to make this smaller and so I didn't have to call openssl... 
		// would have had to do openssl s_client -connect $server:$port < /dev/null 2>/dev/null | openssl x509 -fingerprint -noout -in /dev/stdin | awk -F = '{print $2}'`
		URL vcenterURL=new URL("https://${vcenterHost}/sdk/");
		String thumbprint = CertTool.fingerprint(CertTool.getCerts(vcenterURL)[0]);

		// VM console URL
		//print "http://" . $server . ":" . $htmlPort . "/console/?vmId=" . $vm_mo_ref_id . "&vmName=" . $vMname . "&host=" . $vcenter_fqdn . "&sessionTicket=" . $session . "&thumbprint=" . $vcenterSSLThumbprint . "\n"
		println("http://${vcenterHost}:$htmlPort/console/?vmId=${vmId}&vmName=${vmName}&host=${vcenterHost}&sessionTicket=${sessionTicket}&thumbprint=${thumbprint}");
	}

	static void promptDestroy(ServiceInstance si, String vmName){

		ManagedEntity vmm = new InventoryNavigator(si.rootFolder).searchManagedEntity("VirtualMachine", vmName);
		VirtualMachine vm = (VirtualMachine) vmm;
		System.out.println("Are you sure you want to delete " + vmm.getName());
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		if ("yes".equalsIgnoreCase(br.readLine())) {
			powerOffVm(vm);
			destroyVm(vm);
		}
	}

	static void powerOffVm(VirtualMachine vm){
		Task powerOffTask = vm.powerOffVM_Task();

		while (powerOffTask.getTaskInfo().getState().equals(TaskInfoState.running)) {
			Thread.sleep(2000);
			System.out.println("Percentage complete: " + powerOffTask.getTaskInfo().progress);
		}
		if(!powerOffTask.getTaskInfo().getState().equals(TaskInfoState.success)) {
			Throw new RuntimeException("Poweroff failed for VM: ${vm.getName()}");
		}
		String PowerOffCompletionTime = (new SimpleDateFormat()).format(powerOffTask.getTaskInfo().completeTime.getTime());
		System.out.println("PowerOff finished at " + PowerOffCompletionTime + " with state " + powerOffTask.getTaskInfo().state.toString());
	}

	static void destroyVm(VirtualMachine vm){

		Task destroyTask = vm.destroy_Task();

		while (destroyTask.getTaskInfo().getState().equals(TaskInfoState.running)) {
			Thread.sleep(2000);
			System.out.println("Percentage complete: " + destroyTask.getTaskInfo().progress);
		}
		if (destroyTask.getTaskInfo().getState().equals(TaskInfoState.success)) {
			Throw new RuntimeException("Destroy failed for VM: ${vm.getName()}");

		}
		String destroyCompletionTime = (new SimpleDateFormat()).format(destroyTask.getTaskInfo().completeTime.getTime());
		System.out.println("Destroy finished at " + destroyCompletionTime + " with state " + destroyTask.getTaskInfo().state.toString());
	}


	//Unused unless we want to create new vm in a different location from template
	public static VirtualMachineRelocateSpec createRelocationSpec(Datacenter dc, ConfigObject config) throws RuntimeFault, RemoteException {
		VirtualMachineRelocateSpec vmRelocationSpec = new VirtualMachineRelocateSpec();

		HostSystem hostSystem = (HostSystem) new InventoryNavigator(dc).searchManagedEntity("HostSystem", config.preferedHost);
		if (hostSystem == null) {
			log.severe( "Can't find host: " + config.preferedHost);
			throw new RuntimeException("Can't find host: " + config.preferedHost + " in datacenter: " + dc.getName());
		}
		ComputeResource computeResource = (ComputeResource) new InventoryNavigator(dc).searchManagedEntity("ComputeResource", config.preferedComputeResource);
		if (computeResource == null) {
			log.severe( "Can't find ComputeResource: $config.preferedComputeResource");
			throw new RuntimeException("Can't find ComputeResource: $config.preferedComputeResource in datacenter: " + dc.getName());
		}

		ResourcePool resourcePool = (ResourcePool) new InventoryNavigator(computeResource).searchManagedEntity("ResourcePool", config.preferedResourcePool);
		if (resourcePool == null) {
			log.severe( "Can't find pool: $config.preferedResourcePool");
			throw new RuntimeException("Can't find pool: $config.preferedResourcePool in datacenter: " + dc.getName());
		}

		vmRelocationSpec.host = hostSystem.getMOR();
		vmRelocationSpec.pool = resourcePool.getMOR();

		return vmRelocationSpec;

	}


	public static CustomizationSpec createCustomizationSpec(ConfigObject config) {
		CustomizationSpec customizationSpec = new CustomizationSpec();

		customizationSpec.options = new CustomizationLinuxOptions();

		CustomizationLinuxPrep identity18 = new CustomizationLinuxPrep();
		identity18.domain = config.vmDomain;
		identity18.timeZone = config.vmTimeZone;
		identity18.hwClockUTC = config.vmHWClockUTC;
		identity18.hostName = new CustomizationVirtualMachineName();
		customizationSpec.identity = identity18;

		CustomizationGlobalIPSettings globalIPSettings20 = new CustomizationGlobalIPSettings();
		globalIPSettings20.dnsSuffixList = config.vmDnsSuffixList;
		globalIPSettings20.dnsServerList = config.vmDnsServerList;
		customizationSpec.globalIPSettings = config.globalIPSettings20;

		CustomizationAdapterMapping[] nicSettingMaps21 = new CustomizationAdapterMapping[config.adapterMappings.size()];
		for (int i = 0; i < config.adapterMappings.size(); i++) {
			nicSettingMaps21[i] = createAdapterMapping(config.adapterMappings[i][0], config.adapterMappings[i][1], config.adapterMappings[i][2]);
		}
		customizationSpec.nicSettingMap = nicSettingMaps21;

		return customizationSpec;
	}

	public static CustomizationAdapterMapping createAdapterMapping(String ip, String subnet, String gateway) {
		CustomizationAdapterMapping nicSettings = new CustomizationAdapterMapping();
		CustomizationIPSettings adapter23 = new CustomizationIPSettings();
		CustomizationFixedIp ip24 = new CustomizationFixedIp();
		nicSettings.adapter = adapter23;

		adapter23.ip = ip24;
		ip24.ipAddress = ip;
		adapter23.subnetMask = subnet;
		adapter23.gateway = [gateway,];
		adapter23.primaryWINS = "";
		adapter23.secondaryWINS = "";
		return nicSettings;
	}


	VirtualMachineCloneSpec createCloneSpec(ConfigObject config) throws RemoteException {
		VirtualMachineCloneSpec vmCloneSpec = new VirtualMachineCloneSpec();
		vmCloneSpec.template = false;
		vmCloneSpec.setLocation(createRelocationSpec());
		//vmCloneSpec.setLocation(new VirtualMachineRelocateSpec());

		VirtualMachineConfigSpec vmConfigSpec = new VirtualMachineConfigSpec();
		vmConfigSpec.name = config.instanceName;
		vmConfigSpec.memoryMB = config.memoryMB;
		vmConfigSpec.numCPUs = config.numCPU;
		vmCloneSpec.config = config.vmConfigSpec;
		vmCloneSpec.customization = createCustomizationSpec();

		vmCloneSpec.powerOn = true;
		//Below code creates an pruned json representation of the config but I think a different serializer/deserializer would be needed to make import/export capable configs.
		def denullSpec = denull(new JsonSlurper().parseText((new JsonBuilder(vmCloneSpec).toPrettyString())));
		File specFile = File.createTempFile(config.instanceName,"json");
		specFile.write(new JsonBuilder(denullSpec).toPrettyString());
		return vmCloneSpec;
	}

	public static Object denull(obj) {
  		if(obj instanceof Map) {
    			obj.collectEntries {k, v ->
      				if(v) [(k): denull(v)] else [:]
    			}
  		} else if(obj instanceof List) {
    			obj.collect { denull(it) }.findAll { it != null }
  		} else {
    			obj
  		}
	}

	public static void validateOptionsStrings(ConfigObject config){
		//array of Strings
		[ 'instanceName',
		'dcName',
		'templateName',
		'destFolderName',
		'preferedHost',
		'preferedComputeResource',
		'preferedResourcePool',
		'vmDomain',
		'vmTimeZone' ].each{
			if(config.containsKey(it)){
				if(config[it] instanceof String){
					if(config[it].matches('.*\\w.*')){
						retrun;
					}else{
						throw new IllegalArgumentException("Configuration for $it does not conatain any word characters: '${config[it]}'");
					}
				}else{
					throw new IllegalArgumentException("Configuration for $it should be a String but appears to be something else: '${config[it]}'");
				}
			}else{
				throw new IllegalArgumentException("Configuration for $it is required but not present");
			}
		}
	}
	public static void validateOptionsLongs(ConfigObject config){
		//long
		['memoryMB'].each{
			if( config.containsKey(it) ){
				if(config[it] instanceof String){
					if(config[it].matches('.*\\w.*')){
						retrun;
					}else{
						throw new IllegalArgumentException("Configuration for $it does not conatain any word characters: '${config[it]}'");
					}
				}else{
					throw new IllegalArgumentException("Configuration for $it should be a String but appears to be something else: '${config[it]}'");
				}
			}else{
				throw new IllegalArgumentException("Configuration for $it is required but not present");
			}
		}
	}

	public static void validateOptionsIntegers(ConfigObject config){
		//int
		['numCPU'].each{
			if(config.containsKey(it)){
				if(config[it] instanceof String){
					if(config[it].matches('.*\\w.*')){
						retrun;
					}else{
						throw new IllegalArgumentException("Configuration for $it does not conatain any word characters: '${config[it]}'");
					}
				}else{
					throw new IllegalArgumentException("Configuration for $it should be a String but appears to be something else: '${config[it]}'");
				}
			}else{
				throw new IllegalArgumentException("Configuration for $it is required but not present");
			}
		}
	}
	public static void validateOptionsBooleans(ConfigObject config){
		//boolean
		['vmHWClockUTC'].each{
			if(config.containsKey(it)){
				if(config[it] instanceof String){
					if(config[it].matches('.*\\w.*')){
						retrun;
					}else{
						throw new IllegalArgumentException("Configuration for $it does not conatain any word characters: '${config[it]}'");
					}
				}else{
					throw new IllegalArgumentException("Configuration for $it should be a String but appears to be something else: '${config[it]}'");
				}
			}else{
				throw new IllegalArgumentException("Configuration for $it is required but not present");
			}
		}
	}
	public static void validateOptionsArraysOfStrings(ConfigObject config){
		//array of Strings
		['vmDnsSuffixList',
		'vmDnsServerList'].each{
			if(config.containsKey(it)){
				if(config[it] instanceof String){
					if(config[it].matches('.*\\w.*')){
						retrun;
					}else{
						throw new IllegalArgumentException("Configuration for $it does not conatain any word characters: '${config[it]}'");
					}
				}else{
					throw new IllegalArgumentException("Configuration for $it should be a String but appears to be something else: '${config[it]}'");
				}
			}else{
				throw new IllegalArgumentException("Configuration for $it is required but not present");
			}
		}
	}
	public static void validateOptionsAdapterMapprings(ConfigObject config){
		//array of array of Strings
		['adapterMappings '].each{
			if(config.containsKey(it)){
				if(config[it] instanceof String){
					if(config[it].matches('.*\\w.*')){
						retrun;
					}else{
						throw new IllegalArgumentException("Configuration for $it does not conatain any word characters: '${config[it]}'");
					}
				}else{
					throw new IllegalArgumentException("Configuration for $it should be a String but appears to be something else: '${config[it]}'");
				}
			}else{
				throw new IllegalArgumentException("Configuration for $it is required but not present");
			}
		}
	}

	public static void createVM(ServiceInstance si, ConfigObject config) {
		try {

			def sc = si.getServerConnection();

			def dc = (Datacenter) new InventoryNavigator(si.getRootFolder()).searchManagedEntity("Datacenter", config.dcName);
			if (dc == null) {
				log.severe( "Can't find datacenter: " + config.dcName);
				System.exit(1);
			}

			def templateVM = (VirtualMachine) new InventoryNavigator(dc).searchManagedEntity("VirtualMachine", config.templateName);
			if (templateVM == null) {
				log.severe( "Can't find template: " + config.templateName);
				System.exit(1);
			}

			Folder cloneDestinationFolder = (Folder) new InventoryNavigator(dc).searchManagedEntity("Folder", config.destFolderName);
			if (cloneDestinationFolder == null) {
				log.severe( "Can't find Folder: " + config.destFolderName);
				System.exit(1);
			}

			Task cloneTask = templateVM.cloneVM_Task(cloneDestinationFolder, config.instanceName, createCloneSpec());
			//Task cloneTask = templateVM.cloneVM_Task((Folder)templateVM.getParent(), instanceName, createCloneSpec());

			while (cloneTask.getTaskInfo().getState().equals(TaskInfoState.running)) {
				Thread.sleep(2000);
				System.out.println("Percentage complete: " + cloneTask.getTaskInfo().progress);
			}
			if (cloneTask.getTaskInfo().getState().equals(TaskInfoState.success)) {

			}
			String completionTime = (new SimpleDateFormat()).format(cloneTask.getTaskInfo().completeTime.getTime());
			System.out.println("Creation finished at " + completionTime + " with state " + cloneTask.getTaskInfo().state.toString());
			if(cloneTask.getTaskInfo().state.toString()=="error"){
				System.err.println("ErrorMessage: ${cloneTask.getTaskInfo().error.getLocalizedMessage()} ");
				System.err.println("Reason type: ${cloneTask.getTaskInfo().reason.getDynamicType()} ");
				cloneTask.getTaskInfo().reason.getDynamicProperty().each{
					System.err.println("Reason property name: ${it.name} \tvalue: ${it.val}");
				}
				System.exit(2);
			}

		} catch (MalformedURLException ex) {
			log.severe(* "Invalid URL", ex);
			System.exit(1);
		} catch (RemoteException ex) {
			 log.severe("Error talking to server", ex);
			System.exit(2);
		} catch (InterruptedException ex) {
			log.severe( "Recieved interrupt.", ex);
			System.exit(3);
		} finally {

		}
	}

}
public class DevicePair{
	int controllerKey;
	int unitNumber;
	public String toString(){
		return controllerKey+":"+unitNumber;
	}
	boolean equals(Object o){
		return o.toString().equals(this.toString());
	} 
	public int hashCode(){
		return this.toString().hashCode();
	}
}


/*

	public static void main(String[] args) {
 		def cli = new CliBuilder(usage:'VmCreator [options] hostname')
 		cli.m(longOpt:'mem', args:1, argName:'mem', 'amount of memory in MB')
 		cli.c(longOpt:'cpu', args:1, argName:'cpu', 'number of cpus')
 		cli.h(longOpt:'hostname', args:1, required:1,argName:'hostname', 'name of server to create (must exist in dns)')
 		cli.f(longOpt:'configfile', args:1, required:0,argName:'configfile', 'name of file to read in configuration properties from')
 		cli.u(longOpt:'configurl', args:1, required:0,argName:'configurl', 'name of file to read in configuration properties from')
 		cli.e(longOpt:'environment', args:1, required:0,argName:'environment', 'name of enviroment to read in configuration properties from')
		def options = cli.parse(args)

		//if(args.length!=1){
		if(!options){
			//System.out.println(cli.usage());
			System.exit(2);
		}

                String vcenterHost="vcenter.fqdn";
                URL vcenterURL=new URL("https://${vcenterHost}/sdk/");
                def creds = Netrc.getInstance().getCredentials(vcenterHost);
                if(creds == null){
                        System.err.println("No credentials found, please add a .netrc entry for phx-vcenter01.wineng.owteam.com");
                        System.exit(1);
                }
		String name = options.hostname;
		long mem;
		int cpu;
		String vmIP;
		try{
			InetAddress boxAddr = InetAddress.getByName(name);
			vmIP = boxAddr.getHostAddress();
			if(! vmIP ==~ /"^10.85.*"/){
				log.severe( name+" doesn't resolve to a 10.85 address, please fix or go to hell");
				System.exit(1);
			}
			log.severe( "confirming ip not used");
			if(boxAddr.isReachable(3000)){
				log.severe( name+" is pingable, please pick something else");
				System.exit(1);
			}
		}catch(Exception e){
				log.severe( "Error attempting to resolve name");
				System.exit(1);
		}
		
		String vmStorageIP = vmIP.replaceFirst("^10[.]85","10.22");
		File userprefs=new File("${System.getProperty('user.home')}${System.getProperty('file.separator')}.VmCreator.conf");
		VmCreator vmc = new VmCreator(options.hostname, vmIP, vmStorageIP);
		vmc.config.instanceName = options.hostname;
		if(options.environment){
			if(userprefs.exists())vmc.config.merge(new ConfigSlurper(options.environment).parse(userprefs.toURL()));
			if(options.configurl)vmc.config.merge(new ConfigSlurper(options.environment).parse(new URL(options.configurl)));
			if(options.configfile)vmc.config.merge(new ConfigSlurper(options.environment).parse(new File(options.configfile).toURL()));
		}else{
			if(userprefs.exists())vmc.config.merge(new ConfigSlurper().parse(userprefs.toURL()));
			if(options.configurl)vmc.config.merge(new ConfigSlurper().parse(new URL(options.configurl)));
			if(options.configfile)vmc.config.merge(new ConfigSlurper().parse(new File(options.configfile).toURL()));
		}
		if(options.mem)vmc.config.memoryMB = Long.parseLong(options.mem);
		if(options.cpu)vmc.config.numCPU = Integer.parseInt(options.cpu);
		vmc.siURL=vcenterURL;
		vmc.siUser=creds.getUserName();
		vmc.siPass=creds.getPassword();
		vmc.createVM();
	}

*/
