import java.awt.GraphicsEnvironment;
import java.io.File;

import org.mcmodule.drpatch.DeltarunePatcher;
import org.mcmodule.drpatch.patchsrc.PatchSource;
import org.mcmodule.drpatch.ui.PatchInstallerUI;
import org.mcmodule.drpatch.util.EnumOS;
import org.mcmodule.drpatch.util.EnumPatchType;

public class CommandLineInterface {

	public static void main(String[] args) throws Exception {
		if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
			PatchInstallerUI.main(args);
			return;
		}
		if (args.length < 2) {
			System.out.println("args: <game path> <patch path>");
			return;
		}
		DeltarunePatcher patcher = new DeltarunePatcher(new File(args[0]));
		try (PatchSource patchSource = PatchSource.from(new File(args[1]))){
			EnumOS os = EnumOS.detect();
			EnumPatchType[] types = EnumPatchType.values();
			for (int i = 0; i < types.length; i++) {
				try {
					patcher.patch(patchSource, types[i], os);
				} catch (Exception e) {
					System.err.println("Error while patching " + types[i]);
					e.printStackTrace();
				}
			}
		}
	}

}
