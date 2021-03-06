package com.pwootage.metroidprime

import java.io.{FileWriter, RandomAccessFile}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import com.pwootage.metroidprime.dump._
import com.pwootage.metroidprime.formats.common.PrimeVersion
import com.pwootage.metroidprime.formats.io.PrimeDataFile
import com.pwootage.metroidprime.formats.mrea.MREA
import com.pwootage.metroidprime.formats.scly.Prime1ScriptObjectType
import com.pwootage.metroidprime.randomizer.{Randomizer, RandomizerConfig}
import com.pwootage.metroidprime.templates.{IntScriptProperty, ScriptProperty, ScriptTemplate, ScriptTemplates}
import com.pwootage.metroidprime.utils._
import org.rogach.scallop.{ScallopConf, Subcommand}

object Main {

  class PatcherConf(args: Seq[String]) extends ScallopConf(args) {
    val dump = new Subcommand("dump") {
      val what = trailArg[String](descr = "What to dump")
      val searchDirectory = opt[String]("in", short = 'i', descr = "Path to search (should contain extracted PAKs)", required = true)
      val outDir = opt[String]("out", short = 'o', descr = "Output directory", required = true)
    }
    addSubcommand(dump)

    val extract = new Subcommand("extract") {
      val force = toggle(name = "force", short = 'f', default = Some(false), descrYes = "Overwrite existing files")
      val extractPaks = toggle(name = "paks", short = 'p', default = Some(false), descrYes = "Extract PAKs from ISOs")
      val quieter = toggle(name = "quieter", short = 'q', default = Some(false), descrYes = "Squelch constant PAK extraction messages (will update every 500 files)")
      val destDir = opt[String]("out", short = 'o', descr = "Output directory", required = true)
      val srcFiles = opt[List[String]]("in", short = 'i', descr = "ISO or PAK to parse", required = true)
    }
    addSubcommand(extract)

    val repack = new Subcommand("repack") {
      val force = toggle(name = "force", short = 'f', default = Some(false), descrYes = "Overwrite existing file")
      val quieter = toggle(name = "quieter", short = 'q', default = Some(false), descrYes = "Squelch constant PAK repacking messages (will update every 500 files)")
      val srcDir = opt[String]("in", short = 'i', descr = "Source directory - can be PAK root (list.json) or ISO root (info.json)", required = true)
      val outFile = opt[String]("out", short = 'o', descr = "Target File (.pak or .iso)", required = true)
    }
    addSubcommand(repack)

    val patch = new Subcommand("patch") {
      val force = toggle(name = "force", short = 'f', default = Some(false), descrYes = "Overwrite existing file")
      val quieter = toggle(name = "quieter", short = 'q', default = Some(false), descrYes = "Squelch constant PAK extraction/repacking messages")
      val srcFile = opt[String]("in", short = 'i', descr = "Source ISO", required = true)
      val outFile = opt[String]("out", short = 'o', descr = "Target ISO", required = true)
      val patchfiles = trailArg[List[String]](descr = "Patchfiles to use")
    }
    addSubcommand(patch)

    val diff = new Subcommand("diff") {
      val quieter = toggle(name = "quieter", short = 'q', default = Some(false), descrYes = "Squelch constant PAK extraction/repacking messages")
      val iso1 = opt[String]("in1", short = 'a', descr = "Starting ISO", required = true)
      val iso2 = opt[String]("in2", short = 'b', descr = "Modified ISO", required = true)
      val dest = opt[String]("out", short = 'o', descr = "Destination directory for patches", required = true)
    }
    addSubcommand(diff)

    val randomize = new Subcommand("randomize") {
      val iso = opt[String]("iso", short = 'i', descr = "Source ISO (required to find script objects)", required = true)
      val config = opt[String]("config", short = 'c', descr = "Randomization config JSON file", required = true)
    }
    addSubcommand(randomize)

    val fixTemplateXmls = new Subcommand("fixTemplateXmls") {
      val dir = trailArg[String]()
    }
    addSubcommand(fixTemplateXmls)

    val test = new Subcommand("test") {

    }
    addSubcommand(test)

    val depFind = new Subcommand("depfind") {
      val pakFile = opt[String]("pak", short = 'p', descr = "PAK file to find deps in", required = true)
      val fileID = opt[String]("file", short = 'f', descr = "File ID (8 hex characters)", required = true)
    }
    addSubcommand(depFind)

    verify()
  }


  def fixTemplateUrls(conf: PatcherConf): Unit = {
    import better.files._
    val dir = Paths.get(conf.fixTemplateXmls.dir()).toFile.toScala
    for (d <- dir.list.filter(_.isDirectory)) {
      val base = d.name
      for (file <- d.listRecursively.filter(_.isRegularFile)) {
        val start = new String(file.loadBytes, StandardCharsets.UTF_8)
        val dest = start.replaceAll("""template\s*=\s*"([^\"]+)"""", s"""template="$base/$$1"""")
        file.writeBytes(dest.getBytes(StandardCharsets.UTF_8).iterator)
      }
    }
  }

  def main(args: Array[String]) {
    val conf = new PatcherConf(args)

    if (conf.subcommand.isEmpty) {
      conf.printHelp()
      System.exit(1)
    }
    val command = conf.subcommand.get

    command match {
      case conf.dump => dump(conf)
      case conf.randomize => randomize(conf)
      case conf.extract => extract(conf)
      case conf.repack => repack(conf)
      case conf.patch => patch(conf)
      case conf.test => test()
      case conf.fixTemplateXmls => fixTemplateUrls(conf)
      case conf.diff => diff(conf)
      case conf.depFind => depFind(conf)
    }
  }

  def dump(conf: PatcherConf): Unit = {
    conf.dump.what() match {
      case "collision" => new CollisionDumper().dump(conf.dump.searchDirectory(), conf.dump.outDir())
      case "offsets" => new FileOffsetDumper().dump(conf.dump.searchDirectory(), conf.dump.outDir())
      case "areas" => new AreaDumper().dump(conf.dump.searchDirectory(), conf.dump.outDir())
      case "assets" => new PakAssetIDDumper().dump(conf.dump.searchDirectory(), conf.dump.outDir())
    }
  }

  def extract(conf: PatcherConf): Unit = {
    for (file <- conf.extract.srcFiles()) {
      if (FileIdentifier.isISO(file)) {
        new Extractor(conf.extract.destDir(), conf.extract.force(), conf.extract.extractPaks(), conf.extract.quieter()).extractIso(file)
      } else {
        new Extractor(conf.extract.destDir(), conf.extract.force(), conf.extract.extractPaks(), conf.extract.quieter()).extractPak(file)
      }
    }
  }

  def repack(conf: PatcherConf): Unit = {
    new Repacker(conf.repack.outFile(), conf.repack.force(), conf.repack.quieter()).repack(conf.repack.srcDir())
  }

  def patch(conf: PatcherConf): Unit = {
    val patchfiles = conf.patch.patchfiles().map(f => {
      val path = Paths.get(f)
      val bytes = Files.readAllBytes(path)
      val res = PrimeJacksonMapper.mapper.readValue(bytes, classOf[Patchfile])
      res.patchfileLocation = Some(path)
      res
    })

    new Patcher(conf.patch.outFile(), conf.patch.force(), conf.patch.quieter(), patchfiles).patch(conf.patch.srcFile())
  }

  def diff(conf: PatcherConf): Unit = {
    new Differ(conf.diff.quieter()).dif(conf.diff.iso1(), conf.diff.iso2(), conf.diff.dest())
  }

  def depFind(conf: PatcherConf): Unit = {
    FileDepFinder.findDepsOfFileInPak(conf.depFind.fileID(), conf.depFind.pakFile())
  }

  def randomize(conf: PatcherConf): Unit = {
    import better.files._
    val randomizerConfig = PrimeJacksonMapper.mapper.readValue(
      conf.randomize.config().toFile.contentAsString,
      classOf[RandomizerConfig]
    )
    new Randomizer(randomizerConfig).naiveRandomize(conf.randomize.iso())
  }

  def test(): Unit = {
    val inf = "out/62b0d67d.MREA"
    //    val inf = "out/b2701146.MREA-mp1"
    val raf = new RandomAccessFile(inf, "r")

    val pdf = new PrimeDataFile(Some(raf), Some(raf))

    val mrea = new MREA
    mrea.read(pdf)

    val raf2 = new RandomAccessFile(inf + "-out", "rw")
    val pdf2 = new PrimeDataFile(Some(raf2), Some(raf2))

    mrea.write(pdf2)
    raf2.setLength(raf2.getFilePointer)
    raf2.seek(0)

    val pdf3 = new PrimeDataFile(Some(raf2), Some(raf2))
    mrea.read(pdf3)
  }
}
