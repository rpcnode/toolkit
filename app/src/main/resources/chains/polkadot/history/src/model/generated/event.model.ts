import {Entity as Entity_, Column as Column_, PrimaryColumn as PrimaryColumn_, Index as Index_, IntColumn as IntColumn_, StringColumn as StringColumn_, DateTimeColumn as DateTimeColumn_, JSONColumn as JSONColumn_} from "@subsquid/typeorm-store"

@Index_("idx_event_pallet_name_block_number_5ec2090c", ["pallet", "name", "blockNumber"], {unique: false})
@Entity_()
export class Event {
    constructor(props?: Partial<Event>) {
        Object.assign(this, props)
    }

    @PrimaryColumn_()
    id!: string

    @Index_("idx_event_block_number_367674b2")
    @IntColumn_({nullable: false})
    blockNumber!: number

    @IntColumn_({nullable: false})
    index!: number

    @IntColumn_({nullable: true})
    extrinsicIndex!: number | undefined | null

    @StringColumn_({nullable: false})
    phase!: string

    @StringColumn_({nullable: false})
    pallet!: string

    @StringColumn_({nullable: false})
    name!: string

    @DateTimeColumn_({nullable: false})
    timestamp!: Date

    @JSONColumn_({nullable: true})
    args!: unknown | undefined | null
}
