package com.mlreef.rest.api.v1

import com.mlreef.rest.Account
import com.mlreef.rest.DataProjectRepository
import com.mlreef.rest.PipelineConfig
import com.mlreef.rest.PipelineConfigRepository
import com.mlreef.rest.PipelineInstance
import com.mlreef.rest.PipelineInstanceRepository
import com.mlreef.rest.api.v1.dto.PipelineConfigDto
import com.mlreef.rest.api.v1.dto.PipelineInstanceDto
import com.mlreef.rest.api.v1.dto.toDto
import com.mlreef.rest.exceptions.ErrorCode
import com.mlreef.rest.exceptions.MethodNotAllowedException
import com.mlreef.rest.exceptions.NotFoundException
import com.mlreef.rest.external_api.gitlab.TokenDetails
import com.mlreef.rest.feature.pipeline.PipelineService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PostFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID
import java.util.logging.Logger

@RestController
@RequestMapping("/api/v1/pipelines")
class PipelineController(
    val pipelineService: PipelineService,
    val dataProjectRepository: DataProjectRepository,
    val pipelineConfigRepository: PipelineConfigRepository,
    val pipelineInstanceRepository: PipelineInstanceRepository,
) {
    private val log: Logger = Logger.getLogger(ExperimentsController::class.simpleName)

    private fun beforeGetPipelineConfig(id: UUID): PipelineConfig {
        return pipelineConfigRepository.findByIdOrNull(id)
            ?: throw NotFoundException(ErrorCode.NotFound, "PipelineConfig was not found")
    }

    @GetMapping
    @PostFilter("postCanViewPipeline()")
    fun getAllPipelineConfigs(): List<PipelineConfigDto> {
        val list: List<PipelineConfig> = pipelineConfigRepository.findAll().toList()
        return list.map {
            val instances = pipelineInstanceRepository.findAllByPipelineConfigId(it.id)
            it.toDto(instances.map(PipelineInstance::toDto))
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("canViewPipeline(#id)")
    fun getPipelineConfig(@PathVariable id: UUID): PipelineConfigDto {
        val config = beforeGetPipelineConfig(id)
        val instances = pipelineInstanceRepository.findAllByPipelineConfigId(config.id)
        return config.toDto(instances.map(PipelineInstance::toDto))
    }

    @GetMapping("/{pid}/instances")
    @PreAuthorize("canViewPipeline(#pid)")
    fun getAllPipelineInstancesFromConfig(@PathVariable pid: UUID): List<PipelineInstanceDto> {
        beforeGetPipelineConfig(pid)
        val instances = pipelineInstanceRepository.findAllByPipelineConfigId(pid)
        return instances.map(PipelineInstance::toDto)
    }

    @GetMapping("/{pid}/instances/{id}")
    @PreAuthorize("canViewPipeline(#pid)")
    fun getOnePipelineInstanceFromConfig(@PathVariable pid: UUID, @PathVariable id: UUID): PipelineInstanceDto {
        beforeGetPipelineConfig(pid)
        return beforeGetPipelineInstance(pid, id)
            .toDto()
    }

    @PostMapping("/{pid}/instances")
    @PreAuthorize("hasAccessToPipeline(#pid,'DEVELOPER')")
    fun createPipelineInstanceForConfig(@PathVariable pid: UUID): PipelineInstanceDto {
        val pipelineConfig = beforeGetPipelineConfig(pid)
        val instances = pipelineInstanceRepository.findAllByPipelineConfigId(pid)

        val nextNumber = if (instances.isEmpty()) {
            log.info("No PipelineInstances so far, start with 1 as first iteration ")
            1
        } else {
            instances.map { it.number }.max()!! + 1
        }

        val createInstance = pipelineInstanceRepository.save(pipelineConfig.createInstance(nextNumber))
        log.info("Created new Instance $createInstance for Pipeline $createInstance")
        return createInstance.toDto()
    }

    @PutMapping("/{pid}/instances/{id}/{action}")
    @PreAuthorize("hasAccessToPipeline(#pid,'DEVELOPER')")
    fun updatePipelineInstanceFromConfig(
        @PathVariable pid: UUID,
        @PathVariable id: UUID,
        @PathVariable action: String,
        tokenDetails: TokenDetails,
        account: Account,
    ): PipelineInstanceDto {
        val pipelineConfig = beforeGetPipelineConfig(pid)
        val instance = beforeGetPipelineInstance(pid, id)

        val dataProject = dataProjectRepository.findByIdOrNull(pipelineConfig.dataProjectId)
            ?: throw NotFoundException(ErrorCode.NotFound, "dataProject not found for this Pipeline")

        val adaptedInstance = when (action) {
            "start" -> pipelineService.startInstance(account, tokenDetails.accessToken, dataProject.gitlabId, instance, secret = pipelineService.createSecret())
            "archive" -> pipelineService.archiveInstance(instance)
            "cancel" -> pipelineService.cancelInstance(instance)
            else -> throw MethodNotAllowedException(ErrorCode.NotFound, "No valid action: '$action'")
        }

        return adaptedInstance.toDto()
    }

    @GetMapping("/{pid}/instances/{id}/mlreef-file", produces = [MediaType.TEXT_PLAIN_VALUE])
    @PreAuthorize("hasAccessToPipeline(#pid,'DEVELOPER')")
    fun getExperimentYaml(
        @PathVariable pid: UUID,
        @PathVariable id: UUID,
        account: Account,
    ): String {
        beforeGetPipelineConfig(pid)

        val instance = beforeGetPipelineInstance(pid, id)
        return pipelineService.createPipelineInstanceFile(pipelineInstance = instance, author = account, secret = instance.pipelineJobInfo?.secret
            ?: "***censored***")
    }

    private fun beforeGetPipelineInstance(pid: UUID, id: UUID) =
        (pipelineInstanceRepository.findOneByPipelineConfigIdAndId(pid, id)
            ?: throw NotFoundException(ErrorCode.NotFound, "PipelineInstance was not found"))

    @DeleteMapping("/{configId}/instances/{instanceId}")
    @PreAuthorize("hasAccessToPipeline(#configId,'DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePipelineInstanceFromConfig(
        @PathVariable configId: UUID,
        @PathVariable instanceId: UUID,
        tokenDetails: TokenDetails,
    ) {
        beforeGetPipelineConfig(configId)

        val instance = beforeGetPipelineInstance(configId, instanceId)

        val dataProject = dataProjectRepository.findByIdOrNull(instance.dataProjectId)
            ?: throw NotFoundException(ErrorCode.NotFound, "DataProject was not found")

        pipelineService.deletePipelineInstance(tokenDetails.accessToken, dataProject.gitlabId, instance.targetBranch)
        pipelineInstanceRepository.delete(instance)
    }
}


