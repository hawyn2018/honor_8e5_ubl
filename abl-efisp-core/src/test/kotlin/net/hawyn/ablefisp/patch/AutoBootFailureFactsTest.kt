package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AutoBootFailureFactsTest {
    private data class Sample(val image:PeImage,val shutdownCall:Long,val abortTail:Long,
        val callbackTail:Long,val poll:Long?,val manual:Long,val methodLoad:Long,val callbackGuardStart:Long)

    /** Independent PE/raw mapping with real EFI table provenance and movable registers. */
    private fun fixture(shift:Int=0,register:Int=8,reorder:Boolean=false,withPoll:Boolean=true,
                        withCallback:Boolean=true):Sample {
        val code=0x1000L+shift;val data=0x9000L+shift;val raw=0x400
        val b=ByteArray(0x6400)
        b[0]=0x4d;b[1]=0x5a;b.putWord(0x3c,0x80);b.putWord(0x80,0x4550)
        b.putWord(0x84,0x0002aa64);b.putWord(0x94,0x202200f0);b.putWord(0x98,0x20b)
        b.putWord(0xa8,code.toInt());b.putWord(0xb8,0x1000);b.putWord(0xbc,0x200)
        b.putWord(0xd0,(data+0x2000).toInt());b.putWord(0xd4,0x400);b.putWord(0x104,16)
        listOf(Triple(code,raw,0x60000020),Triple(data,0x4400,0xc0000040.toInt())).forEachIndexed { n,(rva,offset,flags) ->
            val s=0x188+n*40;(if(n==0)".text" else ".data").toByteArray().copyInto(b,s)
            b.putWord(s+8,if(n==0)0x4000 else 0x2000);b.putWord(s+12,rva.toInt())
            b.putWord(s+16,if(n==0)0x4000 else 0x2000);b.putWord(s+20,offset);b.putWord(s+36,flags)
        }
        val map=PeImage.parse(b)
        fun put(pc:Long,w:Int)=b.putWord(requireNotNull(map.rvaToOffset(pc,4)),w)
        fun qword(pc:Long,w:Long){put(pc,w.toInt());put(pc+4,(w ushr 32).toInt())}
        fun text(pc:Long,s:String)=(s.toByteArray()+byteArrayOf(0)).copyInto(b,requireNotNull(map.rvaToOffset(pc,s.length+1)))
        fun adr(pc:Long,rd:Int,to:Long) {
            val d=to-pc;put(pc,0x10000000 or (((d and 3) shl 29).toInt()) or ((((d shr 2) and 0x7ffff).toInt()) shl 5) or rd)
        }
        fun ldr(rt:Int,rn:Int,off:Int=0,width:Int=8)=
            (if(width==8)0xf9400000.toInt() else 0xb9400000.toInt()) or ((off/width) shl 10) or (rn shl 5) or rt
        fun str(rt:Int,rn:Int,off:Int=0)=0xf9000000.toInt() or ((off/8) shl 10) or (rn shl 5) or rt
        fun cbz(pc:Long,r:Int,to:Long)=0xb4000000.toInt() or ((((to-pc) shr 2).toInt() and 0x7ffff) shl 5) or r
        fun condition(pc:Long,to:Long,cond:Int)=0x54000000 or ((((to-pc) shr 2).toInt() and 0x7ffff) shl 5) or cond
        val shutdown=code+0x200;val boot=code+0x400;val abort=code+0x800;val manual=code+0xa00
        val log=code+0xc00;val cleanup=code+0xe00;val callback=code+0x1000
        val registration=code+0x1400;val delay=code+0x1600;val event=data+0x80
        text(data+0x100,"Boot State is : %d\n");text(data+0x140,"Your device will shutdown in 30s\n")
        text(data+0x200,"avb_abort!");text(data+0x240,"Exit key detection timer\n")
        text(data+0x280,"reboot");text(data+0x2a0,"oem shutdown");text(data+0x2c0,"continue")
        qword(data+0x300,data+0x280);qword(data+0x308,manual)
        qword(data+0x310,data+0x2a0);qword(data+0x318,shutdown)
        qword(data+0x320,data+0x2c0);qword(data+0x328,boot)
        var p=code
        fun emit(w:Int):Long{val at=p;put(p,w);p+=4;return at}
        fun address(r:Int,to:Long){adr(p,r,to);p+=4}
        fun call(to:Long)=emit(A64.branch(p,to) or 0x80000000.toInt())
        fun nop(){if(reorder)emit(0xd503201f.toInt())}
        // Entry stores image/system/boot/runtime roles from standard SYSTEM_TABLE fields.
        emit(0xd503233f.toInt());emit(0xaa0103f4.toInt());address(9,data)
        emit(str(0,9));emit(str(20,9,8));emit(ldr(10,20,0x60));emit(str(10,9,16))
        emit(ldr(11,20,0x58));emit(str(11,9,24));emit(0xd65f03c0.toInt())
        p=shutdown;emit(0xd503233f.toInt());emit(0xd10083ff.toInt());emit(str(30,31,16))
        address(register,data+24);emit(ldr(register,register))
        val methodLoad=emit(ldr(register,register,0x68));nop();emit(0x52800040)
        emit(0xd63f0000.toInt() or (register shl 5));emit(ldr(30,31,16));emit(0x910083ff.toInt())
        emit(0xd50323bf.toInt());emit(0xd65f03c0.toInt())
        var shutdownCall=0L;var poll:Long?=null
        fun bootState(start:Long,includePoll:Boolean):Long {
            p=start;emit(0xd503233f.toInt());address(1,data+0x100);call(log)
            address(19,data+0x800);emit(ldr(register,19,8,4))
            emit(0x7100041f or (register shl 5));val eq=emit(0);nop()
            emit(0x7100081f or (register shl 5));val err=emit(0);nop()
            emit(0x71000c1f or (register shl 5));val other=emit(0)
            val failure=p;address(1,data+0x140);call(log);nop();val result=call(shutdown)
            val done=p;emit(0xd65f03c0.toInt())
            put(eq,condition(eq,done,0));put(err,condition(err,failure,0));put(other,condition(other,done,1))
            if(includePoll) {
                address(21,event);val load=p;emit(ldr(register,21));val empty=emit(0)
                emit(0x5284e200);call(delay);val back=emit(A64.branch(p,load));val end=p;emit(0xd65f03c0.toInt())
                put(empty,cbz(empty,register,end));poll=back
            }
            return result
        }
        shutdownCall=bootState(boot,withPoll)
        // A manual reboot root deliberately shares the recognizable error-shaped text.
        // Discovery may inspect it, but must not create an automatic recovery hook there.
        bootState(manual,false)
        p=abort;emit(0xd503233f.toInt());emit(0xd10083ff.toInt());emit(str(30,31,16))
        address(1,data+0x200);call(log);emit(ldr(30,31,16));emit(0x910083ff.toInt());emit(0xd50323bf.toInt())
        val abortTail=emit(A64.branch(p,shutdown))
        p=log;emit(0xd503233f.toInt());emit(0xd65f03c0.toInt())
        val save19and20=0xa9000000.toInt() or (4 shl 15) or (20 shl 10) or (31 shl 5) or 19
        val load19and20=save19and20 or 0x00400000
        p=cleanup;emit(0xd503233f.toInt());emit(0xd10103ff.toInt());emit(str(30,31,16));emit(save19and20)
        address(19,event);address(register,data+16)
        emit(ldr(register,register));emit(ldr(register,register,0x70));emit(ldr(0,19))
        emit(0xd63f0000.toInt() or (register shl 5))
        emit(str(31,19));emit(str(31,19,8)) // Another UI global is also cleared: only CloseEvent's slot is relevant.
        address(1,data+0x240);emit(load19and20);emit(ldr(30,31,16));emit(0x910103ff.toInt());emit(0xd50323bf.toInt());emit(0xd65f03c0.toInt())
        p=callback;emit(0xd503233f.toInt());emit(0xd10103ff.toInt());emit(str(30,31,16));emit(save19and20)
        emit(0xaa0103f3.toInt()) // MOV X19,X1, the warning descriptor.
        emit(0xeb09011f.toInt());val notExpired=emit(0);call(cleanup)
        val callbackGuardStart=p
        emit(ldr(17,19,0x38,4));emit(0x71000c1f or (17 shl 5));val type3=emit(0)
        emit(0x7100041f or (17 shl 5));val otherTypes=emit(0)
        emit(ldr(8,19));emit(ldr(18,8,0x20,4));val continueAction=emit(0)
        val fatal=p;emit(load19and20);emit(ldr(30,31,16));emit(0xd503201f.toInt())
        emit(0x910103ff.toInt());emit(0xd50323bf.toInt())
        val callbackTail=emit(A64.branch(p,shutdown));val key=p
        emit(load19and20);emit(ldr(30,31,16));emit(0x910103ff.toInt());emit(0xd50323bf.toInt());emit(0xd65f03c0.toInt())
        put(notExpired,condition(notExpired,key,9))
        put(type3,condition(type3,fatal,0));put(otherTypes,condition(otherTypes,key,8))
        put(continueAction,0x35000000 or ((((key-continueAction) shr 2).toInt() and 0x7ffff) shl 5) or 18)
        p=registration;emit(0xd503233f.toInt())
        if(withCallback) {
            address(2,callback);address(register,data+16);emit(ldr(register,register))
            emit(ldr(register,register,0x50));emit(0xd63f0000.toInt() or (register shl 5))
        }
        emit(0xd65f03c0.toInt())
        p=delay;emit(0xd503233f.toInt());emit(0xd65f03c0.toInt())
        p=delay+0x100;emit(0xd503233f.toInt());emit(0xd65f03c0.toInt())
        return Sample(PeImage.parse(b),shutdownCall,abortTail,callbackTail,poll,manual,methodLoad,callbackGuardStart)
    }
    private fun replace(s:Sample,at:Long,w:Int)=PeImage.parse(s.image.bytes.copyOf().also {
        it.putWord(requireNotNull(s.image.rvaToOffset(at,4)),w)
    })
    private data class ReporterSample(val image:PeImage,val start:Long,val callerCall:Long,
        val helper:Long,val tail:Long,val initializedSlot:Long,val callbackSlot:Long)
    private fun withReturningReporter(s:Sample):ReporterSample {
        val image=s.image;val b=image.bytes.copyOf();val code=image.entryRva
        val data=image.sections.single { it.writable }.rva
        val reporter=code+0x1800;val initializer=code+0x1c00;val helper=code+0x1e00
        val caller=code+0x2200;val log=code+0xc00;val initialized=data+0x900;val callback=data+0xa00
        fun put(pc:Long,w:Int)=b.putWord(requireNotNull(image.rvaToOffset(pc,4)),w)
        fun text(pc:Long,v:String)=(v.toByteArray()+byteArrayOf(0)).copyInto(b,requireNotNull(image.rvaToOffset(pc,v.length+1)))
        fun adr(pc:Long,r:Int,to:Long){val d=to-pc;put(pc,0x10000000 or (((d and 3) shl 29).toInt()) or ((((d shr 2) and 0x7ffff).toInt()) shl 5) or r)}
        fun memory(load:Boolean,r:Int,n:Int,off:Int=0,width:Int=8)=
            (if(width==8)0xf9000000.toInt() else 0xb9000000.toInt()) or (if(load)0x00400000 else 0) or ((off/width) shl 10) or (n shl 5) or r
        fun conditional(pc:Long,to:Long,kind:Int,r:Int=0):Int {
            val displacement=((((to-pc) shr 2).toInt() and 0x7ffff) shl 5)
            return if(kind<2)0x54000000 or displacement or kind else (if(kind==2)0x34000000 else 0x35000000) or displacement or r
        }
        text(data+0x400,"bootfail_errno: %X, suggest_method: %d\n")
        text(data+0x480,"system reboot...\n")
        var pc=reporter
        fun emit(w:Int):Long{val at=pc;put(pc,w);pc+=4;return at}
        fun address(r:Int,to:Long){adr(pc,r,to);pc+=4}
        fun call(to:Long)=emit(A64.branch(pc,to) or 0x80000000.toInt())
        val pair19=0xa9000000.toInt() or (4 shl 15) or (20 shl 10) or (31 shl 5) or 19
        val pair21=0xa9000000.toInt() or (6 shl 15) or (22 shl 10) or (31 shl 5) or 21
        emit(0xd503233f.toInt());emit(0xd10103ff.toInt());emit(memory(false,30,31,16))
        emit(pair19);emit(pair21)
        emit(0x2a0003f3);emit(0x2a0203f5) // Preserve suggested action and detail-result input.
        address(1,data+0x400);call(log);address(8,initialized);emit(memory(true,8,8,width=4))
        val notInitialized=emit(0);emit(0x710002bf);val badDetail=emit(0) // CMP W21,#0.
        val nonFatal=emit(0);address(1,data+0x480);call(log)
        address(8,callback);emit(memory(true,8,8));emit(0xd63f0100.toInt())
        val done=pc;emit(pair19 or 0x00400000);emit(pair21 or 0x00400000)
        emit(memory(true,30,31,16));emit(0x910103ff.toInt());emit(0xd50323bf.toInt());emit(0xd65f03c0.toInt())
        put(notInitialized,conditional(notInitialized,done,2,8))
        put(badDetail,conditional(badDetail,done,1));put(nonFatal,conditional(nonFatal,done,3,19))
        pc=initializer;emit(0xd503233f.toInt());address(10,helper);address(9,callback);emit(memory(false,10,9));emit(0xd65f03c0.toInt())
        pc=helper;emit(0xd503233f.toInt());emit(0xd10083ff.toInt());emit(memory(false,30,31,16))
        emit(memory(true,30,31,16));emit(0x910083ff.toInt());emit(0xd50323bf.toInt())
        val tail=emit(A64.branch(pc,code+0x200))
        pc=caller;emit(0xd503233f.toInt());emit(0x910103e1.toInt());emit(0x2a1f03e0)
        val callerCall=call(reporter);emit(0xd65f03c0.toInt())
        pc=code+0x2400;emit(0xd503233f.toInt());emit(0xd65f03c0.toInt())
        return ReporterSample(PeImage.parse(b),reporter,callerCall,helper,tail,initialized,callback)
    }

    @Test fun movedAddressesRegistersAndNopsPreserveSemanticRoles() {
        for(s in listOf(fixture(),fixture(0x18000,12,true),fixture(0x34000,15))) {
            val before=s.image.bytes.copyOf();val r=AutoBootFailureFacts.analyze(s.image)
            assertEquals(s.shutdownCall,r.sites.single { it.kind==AutoBootFailureFacts.Kind.BOOT_STATE_SHUTDOWN }.hookRva)
            assertEquals(s.abortTail,r.sites.single { it.kind==AutoBootFailureFacts.Kind.AVB_ABORT }.hookRva)
            assertTrue(r.sites.all { it.knownManualExclusion })
            assertArrayEquals(before,s.image.bytes)
        }
    }
    @Test fun callbackUsesDeferContractAndHasAnOrdinaryPollResume() {
        val s=fixture();val facts=AutoBootFailureFacts.analyze(s.image)
        val callback=facts.sites.single { it.kind==AutoBootFailureFacts.Kind.WARNING_TIMEOUT_SHUTDOWN }
        assertEquals(s.callbackTail,callback.hookRva)
        assertEquals(AutoBootFailureFacts.ExecutionContext.CALLBACK,callback.executionContext)
        assertTrue(callback.callbackReturnAbiProven);assertNull(callback.naturalReturnRva)
        assertFalse(callback.requiresDeferredPoll);assertEquals(s.poll,callback.safeWaitResumeRva)
        assertTrue(callback.normalContinueGuard.size>=4)
        assertEquals(s.poll,facts.safeWaitResumes.single().hookRva)
        assertEquals(s.image.entryRva+0xe00,facts.safeWaitResumes.single().cleanupRva)
        assertTrue(facts.safeWaitResumes.single().cleanupEvidence.size>=3)
        assertTrue(facts.sites.filter { it.kind!=callback.kind }.all { it.executionContext==AutoBootFailureFacts.ExecutionContext.SYNC })
    }
    @Test fun missingPollIsExplicitRatherThanInventingATplSafeAddress() {
        val r=AutoBootFailureFacts.analyze(fixture(withPoll=false).image)
        val callback=r.sites.single { it.executionContext==AutoBootFailureFacts.ExecutionContext.CALLBACK }
        assertTrue(callback.requiresDeferredPoll);assertNull(callback.safeWaitResumeRva);assertTrue(r.safeWaitResumes.isEmpty())
    }
    @Test fun unregisteredCallbackDoesNotBecomeAnArbitraryShutdownHook() {
        val r=AutoBootFailureFacts.analyze(fixture(withCallback=false).image)
        assertTrue(r.sites.none { it.executionContext==AutoBootFailureFacts.ExecutionContext.CALLBACK })
    }
    @Test fun explicitManualRootIsExcludedButContinueBootErrorsAreIncluded() {
        val s=fixture();val r=AutoBootFailureFacts.analyze(s.image)
        assertTrue(r.manualActionSites.any { it.command=="reboot" && it.handlerRva==s.manual && it.actionCallRvas.isNotEmpty() })
        assertTrue(r.sites.none { it.ownerStart==s.manual })
        assertTrue(r.sites.any { it.hookRva==s.shutdownCall })
        assertTrue(r.manualActionSites.none { it.command=="continue" })
    }
    @Test fun bootServicesField68IsNotAcceptedAsResetSystem() {
        val s=fixture();val old=requireNotNull(s.image.word(s.methodLoad))
        assertThrows(PatchException::class.java) { AutoBootFailureFacts.analyze(replace(s,s.methodLoad,old+(1 shl 10))) }
        val addressPc=s.methodLoad-8
        val env=KblEnvironmentFacts.analyze(s.image)
        val d=env.bootServicesGlobal-addressPc
        val address=0x10000000 or (((d and 3) shl 29).toInt()) or ((((d shr 2) and 0x7ffff).toInt()) shl 5) or 8
        assertThrows(PatchException::class.java) { AutoBootFailureFacts.analyze(replace(s,addressPc,address)) }
    }
    @Test fun damagedCallbackEpilogueFailsBeforeAnUnsafeReturnIsPlanned() {
        val s=fixture()
        assertThrows(PatchException::class.java) { AutoBootFailureFacts.analyze(replace(s,s.callbackTail-8,0xd503201f.toInt())) }
    }
    @Test fun aVolatileResetTypeClobberedByAnIndirectCallIsNotAConstantShutdown() {
        val s=fixture(register=20,reorder=true)
        val bytes=s.image.bytes.copyOf()
        bytes.putWord(requireNotNull(s.image.rvaToOffset(s.methodLoad+4,4)),0x52800040) // MOV W0,#2.
        bytes.putWord(requireNotNull(s.image.rvaToOffset(s.methodLoad+8,4)),0xd63f01e0.toInt()) // BLR X15 clobbers W0.
        assertThrows(PatchException::class.java) { AutoBootFailureFacts.analyze(PeImage.parse(bytes)) }
    }
    @Test fun restoredCalleeSavedRegisterMustRemainRestoredUntilTheTail() {
        val s=fixture()
        // The normal epilogue restored X19, then a new write destroys its ABI value.
        assertThrows(PatchException::class.java) { AutoBootFailureFacts.analyze(replace(s,s.callbackTail-12,0xaa1f03f3.toInt())) }
    }
    @Test fun ordinaryOrangeCountdownKeepsItsOriginalContinueBranch() {
        val s=fixture();val before=s.image.bytes.copyOf()
        val callback=AutoBootFailureFacts.analyze(s.image).sites.single { it.executionContext==AutoBootFailureFacts.ExecutionContext.CALLBACK }
        fun reachesShutdown(mode:Int,action:Int):Boolean {
            var pc=s.callbackGuardStart;var comparison=0;val regs=HashMap<Int,Int>();val seen=HashSet<Long>()
            while(seen.add(pc)) {
                if(pc==callback.hookRva)return true
                val w=s.image.word(pc) ?: return false
                if(w==0xd65f03c0.toInt())return false
                val m=StateBranchEvaluator.access(w)
                when {
                    m?.load==true -> regs[m.rt]=if(m.width==4 && m.rn==19)mode else if(m.width==4 && m.rn==8)action else 0
                    A64.cmpImmediate(w)!=null -> {val c=A64.cmpImmediate(w)!!;comparison=(regs[c.first] ?: 0).compareTo(c.second)}
                    w and 0xff000010.toInt()==0x54000000 -> {
                        val take=when(w and 15){0->comparison==0;8->comparison>0;else->false}
                        if(take){pc+=signExtend(((w ushr 5) and 0x7ffff).toLong(),19) shl 2;continue}
                    }
                    StateBranchEvaluator.booleanEdges(pc,w)!=null -> {
                        val edges=StateBranchEvaluator.booleanEdges(pc,w)!!
                        pc=if(regs[w and 31]==0)edges.first else edges.second;continue
                    }
                }
                pc+=4
            }
            return false
        }
        assertFalse(reachesShutdown(1,9)) // The ordinary five-second orange continue action.
        assertFalse(reachesShutdown(10,9))
        assertFalse(reachesShutdown(0,9))
        assertTrue(reachesShutdown(1,0)) // Only the original explicit terminal defaults.
        assertTrue(reachesShutdown(3,9))
        assertArrayEquals(before,s.image.bytes)
    }
    @Test fun reporterInitializationAndDetailFailuresReturnWithoutARecoveryHook() {
        val r=withReturningReporter(fixture());val before=r.image.bytes.copyOf()
        val facts=AutoBootFailureFacts.analyze(r.image)
        val terminal=facts.sites.single { it.kind==AutoBootFailureFacts.Kind.FATAL_BOOT_REPORT }
        assertEquals(r.tail,terminal.hookRva);assertEquals(r.helper,terminal.ownerStart)
        assertTrue(facts.sites.none { it.ownerStart==r.start || it.hookRva==r.callerCall })
        fun invokesAction(initialized:Boolean,detailFailed:Boolean,mode:Int):Boolean {
            var pc=r.start;var comparison=0;val regs=hashMapOf(0 to mode,2 to if(detailFailed)1 else 0)
            val seen=HashSet<Long>()
            while(seen.add(pc)) {
                val w=r.image.word(pc) ?: return false
                if(w==0xd65f03c0.toInt())return false
                if(w and 0xfffffc1f.toInt()==0xd63f0000.toInt())return regs[(w ushr 5) and 31]?.toLong()==r.helper
                A64.adr(pc,w)?.let { regs[it.first]=it.second.toInt();pc+=4;continue }
                if(A64.blTarget(pc,w)!=null){(0..18).forEach(regs::remove);pc+=4;continue}
                val m=StateBranchEvaluator.access(w)
                when {
                    m?.load==true -> regs[m.rt]=when((regs[m.rn] ?: 0).toLong()+m.offset) {
                        r.initializedSlot->if(initialized)1 else 0
                        r.callbackSlot->r.helper.toInt()
                        else->0
                    }
                    w and 0xffe0ffe0.toInt()==0x2a0003e0 -> regs[w and 31]=regs[(w ushr 16) and 31] ?: 0
                    A64.cmpImmediate(w)!=null -> {val c=A64.cmpImmediate(w)!!;comparison=(regs[c.first] ?: 0).compareTo(c.second)}
                    w and 0xff000010.toInt()==0x54000000 -> if((w and 15==0 && comparison==0)||(w and 15==1 && comparison!=0)) {
                        pc+=signExtend(((w ushr 5) and 0x7ffff).toLong(),19) shl 2;continue
                    }
                    StateBranchEvaluator.booleanEdges(pc,w)!=null -> {
                        val edges=StateBranchEvaluator.booleanEdges(pc,w)!!;pc=if((regs[w and 31] ?: 0)==0)edges.first else edges.second;continue
                    }
                }
                pc+=4
            }
            return false
        }
        assertFalse(invokesAction(false,false,0))
        assertFalse(invokesAction(true,true,0))
        assertFalse(invokesAction(true,false,1))
        assertTrue(invokesAction(true,false,0))
        assertArrayEquals(before,r.image.bytes)
    }

    @Test fun twoActualFamiliesAndConditionalHashPolicyReimport() {
        val root=generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(8).firstOrNull { File(it,"analysis/ablnew-compat/ablnew-inner.pe").isFile }
        assumeTrue("Local original ABL families",root!=null)
        val tails=HashSet<Long>()
        for(path in listOf("analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe","analysis/ablnew-compat/ablnew-inner.pe")) {
            val image=PeImage.parse(File(root,path).readBytes());val original=image.bytes.copyOf()
            val r=AutoBootFailureFacts.analyze(image)
            assertEquals(1,r.sites.count { it.kind==AutoBootFailureFacts.Kind.BOOT_STATE_SHUTDOWN })
            assertEquals(1,r.sites.count { it.kind==AutoBootFailureFacts.Kind.AVB_ABORT })
            assertEquals(2,r.sites.count { it.kind==AutoBootFailureFacts.Kind.FATAL_BOOT_REPORT })
            assertEquals(1,r.sites.count { it.kind==AutoBootFailureFacts.Kind.WARNING_TIMEOUT_SHUTDOWN })
            assertEquals(1,r.safeWaitResumes.size)
            val callback=r.sites.single { it.executionContext==AutoBootFailureFacts.ExecutionContext.CALLBACK }
            assertTrue(callback.callbackReturnAbiProven);assertFalse(callback.requiresDeferredPoll)
            tails+=callback.hookRva
            val policy=BootFailurePolicyPatcher.analyze(image).single()
            val stub=MappedRxCodeAllocator.allocate(image,listOf(MappedRxCodeAllocator.Request(policy.callRva,20))).single().rva
            val patched=image.bytes.copyOf()
            // Test-local historical conditional policy. Production only restores
            // its original BL and never emits this suppressor in a new image.
            val legacyBody=ByteArray(20)
            legacyBody.putWord(0,0x34000080 or policy.allowRegister)
            legacyBody.putWord(4,0x71000c1f or (policy.errorRegister shl 5))
            legacyBody.putWord(8,0x54000041);legacyBody.putWord(12,0xd65f03c0.toInt())
            legacyBody.putWord(16,A64.branch(stub+16,policy.originalTarget))
            patched.putWord(requireNotNull(image.rvaToOffset(policy.callRva,4)),A64.branch(policy.callRva,stub) or 0x80000000.toInt())
            legacyBody.copyInto(patched,requireNotNull(image.rvaToOffset(stub,legacyBody.size)))
            val repeated=AutoBootFailureFacts.analyze(PeImage.parse(patched))
            assertTrue(repeated.sites.none { it.hookRva==policy.callRva || it.existingPolicyStubRva!=null })
            assertTrue(repeated.sites.filter { it.kind==AutoBootFailureFacts.Kind.FATAL_BOOT_REPORT }
                .all { A64.bTarget(it.hookRva,it.originalWord)==it.targetCallee })
            assertEquals(PeImage.parse(patched).word(policy.callRva),
                A64.branch(policy.callRva,stub) or 0x80000000.toInt())
            assertEquals(r.sites.map { it.hookRva },repeated.sites.map { it.hookRva })
            assertArrayEquals(original,image.bytes)
        }
        assertEquals(2,tails.size)
    }
}
