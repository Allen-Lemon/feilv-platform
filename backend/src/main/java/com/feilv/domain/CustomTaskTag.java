package com.feilv.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 保存发布时标签名称快照；后台改名/停用不改写历史任务。 */
@Getter @Setter
@Entity @Table(name = "t_custom_task_tag", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "tag_id"}))
public class CustomTaskTag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Column(name = "tag_id", nullable = false)
    private Long tagId;
    @Column(name = "tag_name", nullable = false, length = 32)
    private String tagName;
}
