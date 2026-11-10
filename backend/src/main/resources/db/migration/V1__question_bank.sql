-- Tables and seed rows are resumable after MySQL implicit DDL commits.
-- Additive schema: existing interview and historical coding tables are retained.
create table if not exists qb_category (
 id bigint auto_increment primary key,
 created_at timestamp(6) not null, updated_at timestamp(6) not null, version bigint not null default 0,
 name varchar(100) not null unique, enabled boolean not null default true
);
create table if not exists qb_topic (
 id bigint auto_increment primary key,
 created_at timestamp(6) not null, updated_at timestamp(6) not null, version bigint not null default 0,
 category_id bigint not null, name varchar(100) not null, enabled boolean not null default true, constraint uq_qb_topic unique(category_id,name), foreign key(category_id) references qb_category(id)
);
create table if not exists qb_tag (
 id bigint auto_increment primary key,
 created_at timestamp(6) not null, updated_at timestamp(6) not null, version bigint not null default 0,
 name varchar(60) not null unique
);
create table if not exists qb_generation_batch (
 id bigint auto_increment primary key,
 created_at timestamp(6) not null, updated_at timestamp(6) not null, version bigint not null default 0,
 idempotency_key varchar(80) not null unique, topic_id bigint not null, requested_count int not null, generated_count int not null default 0, status varchar(32) not null, fence_token bigint not null, foreign key(topic_id) references qb_topic(id),
 index idx_qb_batch_topic(topic_id,created_at)
);
create table if not exists qb_question (
 id bigint auto_increment primary key,
 created_at timestamp(6) not null, updated_at timestamp(6) not null, version bigint not null default 0,
 text text not null, fingerprint varchar(64) not null, topic_id bigint not null, subtopic varchar(100),
 difficulty varchar(24) not null, question_type varchar(24) not null, status varchar(24) not null, source varchar(24) not null,
 quality_score int, relevance_score int, review_attempts int not null default 0, generation_rationale text,
 batch_id bigint, duplicate_of_id bigint,
 foreign key(topic_id) references qb_topic(id), foreign key(batch_id) references qb_generation_batch(id),
 foreign key(duplicate_of_id) references qb_question(id),
 constraint ck_qb_quality check(quality_score between 0 and 100),
 constraint ck_qb_relevance check(relevance_score between 0 and 100),
 constraint ck_qb_status check(status in ('PENDING_REVIEW','APPROVED','REJECTED','DUPLICATE')),
 constraint ck_qb_difficulty check(difficulty in ('BEGINNER','INTERMEDIATE','ADVANCED')),
 index idx_qb_question_browse(status,topic_id,difficulty,question_type),
 index idx_qb_question_fingerprint(fingerprint),
 index idx_qb_question_pending(status,review_attempts,created_at)
);
create table if not exists qb_validation (
 id bigint auto_increment primary key,
 created_at timestamp(6) not null, updated_at timestamp(6) not null, version bigint not null default 0,
 question_id bigint not null, previous_status varchar(24) not null, decision varchar(24) not null,
 quality_score int, relevance_score int, reason text not null, reviewer varchar(150) not null,
 model varchar(150) not null, classification text, duplicate_of_id bigint,
 foreign key(question_id) references qb_question(id),foreign key(duplicate_of_id) references qb_question(id),
 index idx_qb_validation_question(question_id,created_at)
);
create table if not exists qb_agent_execution (
 id bigint auto_increment primary key,
 created_at timestamp(6) not null, updated_at timestamp(6) not null, version bigint not null default 0,
 agent varchar(40) not null, model varchar(150) not null, status varchar(24) not null,
 attempt int not null, fence_token bigint not null, batch_id bigint, question_id bigint,
 finished_at timestamp(6), error varchar(500), summary varchar(500),
 foreign key(batch_id) references qb_generation_batch(id), foreign key(question_id) references qb_question(id),
 index idx_qb_execution_status(status,created_at)
);
create table if not exists qb_question_tag(question_id bigint not null, tag_id bigint not null,
 primary key(question_id,tag_id),foreign key(question_id) references qb_question(id),foreign key(tag_id) references qb_tag(id));
create table if not exists qb_workflow_lock(id int primary key,owner varchar(64),token bigint not null default 0,
 expires_at timestamp(6) not null, next_allowed_at timestamp(6) not null);
-- A date well inside MySQL TIMESTAMP's range stays valid in every supported session timezone.
insert into qb_workflow_lock(id,token,expires_at,next_allowed_at) values(1,0,'2000-01-01 00:00:00','2000-01-01 00:00:00') on duplicate key update id=id;
insert into qb_category(id,name,enabled,created_at,updated_at,version) values(1,'Java',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(1,1,'Core Java',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(2,1,'Collections Framework',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(3,1,'Java 8+',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(4,1,'Streams API',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(5,1,'Multithreading and Concurrency',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(6,1,'JVM and Memory Management',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(7,1,'Exception Handling',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(8,1,'OOP and SOLID Principles',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(9,1,'Design Patterns',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_category(id,name,enabled,created_at,updated_at,version) values(2,'Backend Development',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(10,2,'Spring Framework',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(11,2,'Spring Boot',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(12,2,'Spring Security',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(13,2,'Spring Data JPA and Hibernate',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(14,2,'Microservices',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(15,2,'REST API Design',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(16,2,'Distributed Systems',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(17,2,'Database Fundamentals',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(18,2,'SQL',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(19,2,'Caching',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(20,2,'Messaging Systems',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_category(id,name,enabled,created_at,updated_at,version) values(3,'Frontend Development',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(21,3,'JavaScript',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(22,3,'React',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(23,3,'HTML and CSS',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(24,3,'Frontend Fundamentals',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(25,3,'Browser Internals',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(26,3,'State Management',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(27,3,'Frontend Performance',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_category(id,name,enabled,created_at,updated_at,version) values(4,'Engineering Fundamentals',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(28,4,'Data Structures and Algorithms',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(29,4,'Object-Oriented Design',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(30,4,'System Design',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(31,4,'Software Architecture',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(32,4,'Networking and HTTP',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(33,4,'Operating System Fundamentals',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(34,4,'Git and CI/CD',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(35,4,'Cloud Fundamentals',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
insert into qb_topic(id,category_id,name,enabled,created_at,updated_at,version) values(36,4,'Docker and Containerization',true,current_timestamp,current_timestamp,0) on duplicate key update id=id;
