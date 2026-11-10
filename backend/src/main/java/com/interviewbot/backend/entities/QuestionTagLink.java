package com.interviewbot.backend.entities;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/** Scalar question ID keeps tag filtering independent of the union read model. */
@Entity
@Table(name = "qb_question_tag")
@IdClass(QuestionTagLink.Key.class)
public class QuestionTagLink {
  @Id
  @Column(name = "question_id")
  Long questionId;

  @Id
  @Column(name = "tag_id")
  Long tagId;

  @ManyToOne
  @JoinColumn(name = "tag_id", insertable = false, updatable = false)
  BankTag tag;

  public static class Key implements Serializable {
    public Long questionId;
    public Long tagId;

    public Key() {}

    @Override
    public boolean equals(Object other) {
      return other instanceof Key k
          && Objects.equals(questionId, k.questionId)
          && Objects.equals(tagId, k.tagId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(questionId, tagId);
    }
  }
}
