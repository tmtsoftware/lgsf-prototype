package lgsf.btoprototypedeploy

import csw.framework.deploy.containercmd.ContainerCmd
import csw.prefix.models.Subsystem

object BtoPrototypeContainerCmdApp {

  def main(args: Array[String]): Unit = {
    ContainerCmd.start("bto-prototype_container_cmd_app", Subsystem.withNameInsensitive("LGSF"), args)
  }
}
